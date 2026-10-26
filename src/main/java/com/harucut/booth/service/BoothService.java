package com.harucut.booth.service;

import com.harucut.booth.dto.BoothDtos;
import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.entity.BoothPairing;
import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.booth.repository.BoothPairingRepository;
import com.harucut.booth.security.BoothPrincipal;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.config.RateLimiter;
import com.harucut.event.dto.PublicEventResponse;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.PublicEventService;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.config.ParticipationProperties;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.entity.CaptureRun;
import com.harucut.participant.enums.CaptureRunStatus;
import com.harucut.participant.repository.CaptureRunRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.participant.service.ParticipantService;
import com.harucut.result.dto.ResultTokenResponse;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.result.service.ResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 부스 기기가 부르는 API (ADR-0007 §7). 기기 토큰으로 할 수 있는 것은 다음 손님 세션을 열고 회차를 보고 닫는 것까지다 —
 * 업로드·합성·사진 조회는 손님의 참가자 토큰으로만 한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BoothService {

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final BoothDeviceRepository deviceRepository;
    private final BoothPairingRepository pairingRepository;
    private final EventRepository eventRepository;
    private final CaptureRunRepository captureRunRepository;
    private final ComposeJobRepository composeJobRepository;
    private final ParticipantSessionRepository sessionRepository;
    private final ParticipantService participantService;
    private final PublicEventService publicEventService;
    private final ResultService resultService;
    private final RateLimiter rateLimiter;
    private final ParticipationProperties properties;
    private final Clock clock;

    /**
     * 연결 코드 교환. 실패 횟수는 예외로 끝나도 남아야 하므로 BusinessException 으로 롤백하지 않는다 —
     * 그래서 이 메서드는 실패 기록 말고는 아무것도 쓰기 전에 실패를 판정한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public BoothDtos.RedeemResponse redeem(String rawCode, String clientIp) {
        if (!rateLimiter.tryAcquire("booth-pairing", clientIp, properties.publicRequestsPerMinute(), RATE_WINDOW)) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
        String code = rawCode.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != 8 || !code.chars().allMatch(c -> BoothDeviceService.ALPHABET.indexOf(c) >= 0)) {
            throw new BusinessException(BoothErrorCode.PAIRING_CODE_INVALID);
        }
        String lookup = code.substring(0, 4);
        LocalDateTime now = now();
        List<BoothPairing> candidates = pairingRepository.findAllByLookupOrderByIdDesc(lookup).stream()
                .filter(pairing -> now.isBefore(pairing.getExpiresAt()))
                .toList();
        Optional<BoothPairing> open = candidates.stream().filter(pairing -> pairing.openAt(now)).findFirst();
        if (open.isEmpty()) {
            boolean locked = candidates.stream().anyMatch(pairing -> pairing.lockedOut(properties.pairingMaxAttempts()));
            throw new BusinessException(locked ? BoothErrorCode.PAIRING_LOCKED : BoothErrorCode.PAIRING_CODE_INVALID);
        }

        BoothPairing pairing = open.get();
        if (!OpaqueTokens.hash(code.substring(4)).equals(pairing.getSecretHash())) {
            pairing.fail(properties.pairingMaxAttempts());
            throw new BusinessException(pairing.lockedOut(properties.pairingMaxAttempts())
                    ? BoothErrorCode.PAIRING_LOCKED : BoothErrorCode.PAIRING_CODE_INVALID);
        }
        if (pairingRepository.redeem(pairing.getId()) != 1) {
            // 같은 코드가 동시에 두 번 들어와 다른 쪽이 먼저 소비했다
            throw new BusinessException(BoothErrorCode.PAIRING_CODE_INVALID);
        }
        BoothDevice device = deviceRepository.findById(pairing.getBoothDeviceId())
                .filter(found -> !found.isRevoked())
                .orElseThrow(() -> new BusinessException(BoothErrorCode.DEVICE_UNAVAILABLE));
        String token = OpaqueTokens.generate();
        device.pair(OpaqueTokens.hash(token));
        device.seen(now);
        Event event = eventRepository.findById(device.getEventId()).orElseThrow();
        return new BoothDtos.RedeemResponse(token, device.getPublicId(), event.getPublicId());
    }

    public BoothDtos.MeResponse me(BoothPrincipal principal) {
        BoothDevice device = device(principal);
        device.seen(now());
        Event event = eventRepository.findById(device.getEventId()).orElseThrow();
        return new BoothDtos.MeResponse(device.getPublicId(), device.getName(), device.getStatus(), publicEvent(event));
    }

    // 게시 전·보관 종료면 참가 정보가 없다 — 기기 화면은 대기로 둔다(404 로 기기를 멈추게 하지 않는다)
    private PublicEventResponse publicEvent(Event event) {
        try {
            return publicEventService.view(event);
        } catch (BusinessException e) {
            if (e.getErrorCode() == GlobalErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /** 다음 손님 — 새 Participant(BOOTH) 와 RESTRICTED 세션(30분) */
    public ParticipantDtos.SessionResponse nextParticipant(BoothPrincipal principal) {
        BoothDevice device = device(principal);
        Event event = eventRepository.findById(device.getEventId())
                .orElseThrow(() -> new BusinessException(BoothErrorCode.DEVICE_UNAVAILABLE));
        return participantService.createBoothSession(event, device.getId());
    }

    /** 지금 회차. version 이 서버 stateVersion 과 다르면 작업을 싣지 않는다 — 늦게 온 이전 결과를 새 손님 화면에 보이지 않게 */
    @Transactional(readOnly = true)
    public BoothDtos.RunView current(BoothPrincipal principal, Integer version) {
        return captureRunRepository.findByActiveBoothDeviceId(principal.deviceId())
                .filter(run -> run.activeAt(now()))
                .map(run -> view(run, version != null && version == run.getStateVersion()))
                .orElse(null);
    }

    /** 회차를 닫고 그 손님 세션을 끝낸다 — 다음 손님은 세션 발급부터 */
    public BoothDtos.RunView close(BoothPrincipal principal, String captureRunId, int stateVersion) {
        CaptureRun run = ownRun(principal, captureRunId, stateVersion);
        if (run.getStatus() == CaptureRunStatus.ACTIVE) {
            run.close(now());
        }
        sessionRepository.revokeAllByParticipantId(run.getParticipantId());
        return view(run, false);
    }

    /** 결과 토큰 재발급 — 명시적으로 부를 때만(폴링으로 토큰 행이 쌓이지 않게). 원문은 해시로만 남아 다시 보여 줄 수 없다 */
    public ResultTokenResponse resultAccess(BoothPrincipal principal, String captureRunId, int stateVersion) {
        CaptureRun run = ownRun(principal, captureRunId, stateVersion);
        ComposeJob job = composeJobRepository.findFirstByCaptureRunIdOrderByIdDesc(run.getId())
                .orElseThrow(() -> new BusinessException(ResultErrorCode.NOT_READY));
        Event event = eventRepository.findById(run.getEventId()).orElseThrow();
        return resultService.issue(job, event);
    }

    private CaptureRun ownRun(BoothPrincipal principal, String captureRunId, int stateVersion) {
        CaptureRun run = captureRunRepository.findByPublicId(captureRunId)
                .filter(found -> principal.deviceId().equals(found.getBoothDeviceId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (run.getStateVersion() != stateVersion) {
            throw new BusinessException(BoothErrorCode.STATE_VERSION_CONFLICT);
        }
        return run;
    }

    private BoothDtos.RunView view(CaptureRun run, boolean withJob) {
        BoothDtos.RunView.Job job = withJob
                ? composeJobRepository.findFirstByCaptureRunIdOrderByIdDesc(run.getId())
                        .map(found -> new BoothDtos.RunView.Job(found.getId(), found.getStatus()))
                        .orElse(null)
                : null;
        return new BoothDtos.RunView(run.getPublicId(), run.getStateVersion(), run.getStatus(), job);
    }

    private BoothDevice device(BoothPrincipal principal) {
        return deviceRepository.findById(principal.deviceId())
                .filter(found -> !found.isRevoked())
                .orElseThrow(() -> new BusinessException(BoothErrorCode.DEVICE_UNAVAILABLE));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
