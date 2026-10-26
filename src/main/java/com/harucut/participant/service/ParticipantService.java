package com.harucut.participant.service;

import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.service.AssetService;
import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.config.RateLimiter;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventFrameRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.config.ParticipationProperties;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.entity.CaptureRun;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.entity.ParticipantConsent;
import com.harucut.participant.entity.ParticipantSession;
import com.harucut.participant.enums.CaptureRunStatus;
import com.harucut.participant.enums.ConsentDecision;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.enums.SessionStatus;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.participant.repository.CaptureRunRepository;
import com.harucut.participant.repository.ParticipantConsentRepository;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.result.dto.ResultTokenResponse;
import com.harucut.result.service.ResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 행사 참가자 흐름 (ADR-0007 §5): 세션 → 동의 → 회차 → 업로드 → (합성은 ParticipantComposeService) → 결과.
 * 일반 비회원에게는 아무것도 열지 않는다 — 모든 쓰기는 유효한 참가자 세션으로만 된다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ParticipantService {

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final EventRepository eventRepository;
    private final EventExperienceVersionRepository experienceVersionRepository;
    private final EventFrameRepository eventFrameRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantSessionRepository sessionRepository;
    private final ParticipantConsentRepository consentRepository;
    private final CaptureRunRepository captureRunRepository;
    private final ComposeJobRepository composeJobRepository;
    private final AssetService assetService;
    private final FrameVersionService frameVersionService;
    private final ResultService resultService;
    private final RateLimiter rateLimiter;
    private final ParticipationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ── 세션 ──────────────────────

    public ParticipantDtos.SessionResponse createByodSession(String slug, String clientIp) {
        if (!rateLimiter.tryAcquire("participant-session", clientIp, properties.publicRequestsPerMinute(), RATE_WINDOW)) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
        Event event = eventRepository.findBySlug(slug)
                .filter(found -> found.getCurrentExperienceVersionId() != null)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (!event.isByodEnabled()) {
            throw new BusinessException(EventErrorCode.CHANNEL_NOT_ALLOWED);
        }
        requireCaptureOpen(event);
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        return issueSession(participant, properties.byodSessionTtl());
    }

    /** 부스가 다음 손님을 시작할 때(P2A-4) — 새 참가자와 RESTRICTED 세션 */
    public ParticipantDtos.SessionResponse createBoothSession(Event event, Long boothDeviceId) {
        if (!event.isBoothEnabled()) {
            throw new BusinessException(EventErrorCode.CHANNEL_NOT_ALLOWED);
        }
        requireCaptureOpen(event);
        Participant participant = participantRepository.save(Participant.booth(event.getId(), boothDeviceId));
        return issueSession(participant, properties.boothSessionTtl());
    }

    private ParticipantDtos.SessionResponse issueSession(Participant participant, Duration ttl) {
        String token = OpaqueTokens.generate();
        LocalDateTime expiresAt = now().plus(ttl);
        sessionRepository.save(ParticipantSession.restricted(participant.getId(), OpaqueTokens.hash(token), expiresAt));
        return new ParticipantDtos.SessionResponse(token, participant.getPublicId(), SessionStatus.RESTRICTED, offset(expiresAt));
    }

    @Transactional(readOnly = true)
    public ParticipantDtos.SessionView session(ParticipantPrincipal principal) {
        ParticipantSession session = currentSession(principal);
        Event event = event(principal.eventId());
        EventExperienceDocument experience = currentExperience(event);
        Map<String, ParticipantConsent> latest = latestDecisions(principal.participantId());
        List<ParticipantDtos.SessionView.PendingConsent> pending = requiredItems(experience).stream()
                .filter(item -> !agreed(latest.get(item.code()), item.version()))
                .map(item -> new ParticipantDtos.SessionView.PendingConsent(item.code(), item.version()))
                .toList();
        ParticipantDtos.RunView run = captureRunRepository
                .findFirstByParticipantIdAndStatusOrderByIdDesc(principal.participantId(), CaptureRunStatus.ACTIVE)
                .filter(found -> found.activeAt(now()))
                .map(this::runView)
                .orElse(null);
        return new ParticipantDtos.SessionView(principal.participantPublicId(), event.getPublicId(), event.getSlug(),
                principal.channel(), session.getStatus(), offset(session.getExpiresAt()), pending, run);
    }

    // ── 동의 ──────────────────────

    public ParticipantDtos.ConsentResponse consent(ParticipantPrincipal principal, String eventPublicId,
                                                   ParticipantDtos.ConsentRequest request) {
        Event event = requireOwnEvent(principal, eventPublicId);
        ParticipantSession session = currentSession(principal);
        EventExperienceDocument experience = currentExperience(event);
        Map<String, EventSettings.ConsentItem> offered = new HashMap<>();
        experience.consentItems().forEach(item -> offered.put(item.code(), item));

        LocalDateTime now = now();
        for (ParticipantDtos.ConsentRequest.ConsentItem item : request.items()) {
            EventSettings.ConsentItem current = offered.get(item.code());
            if (current == null || !current.version().equals(item.version())) {
                // 행사에 없는 항목이거나 옛 버전 문구에 대한 동의 — 기록하지 않고 거절한다
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                        "현재 행사의 동의 항목·버전이 아닙니다: " + item.code() + " v" + item.version());
            }
            consentRepository.save(new ParticipantConsent(principal.participantId(), event.getId(), item.code(),
                    item.version(), item.decision(), principal.channel(), event.getCurrentExperienceVersionId(), now));
        }

        Map<String, ParticipantConsent> latest = latestDecisions(principal.participantId());
        boolean satisfied = requiredItems(experience).stream().allMatch(item -> agreed(latest.get(item.code()), item.version()));
        if (satisfied) {
            session.activate();
        } else {
            session.restrict();
        }
        return new ParticipantDtos.ConsentResponse(session.getStatus());
    }

    // ── 회차 ──────────────────────

    public ParticipantDtos.RunStartResponse startRun(ParticipantPrincipal principal, String eventPublicId) {
        Event event = requireOwnEvent(principal, eventPublicId);
        requireActive(principal);
        requireCaptureOpen(event);
        if (principal.isBooth()) {
            releaseExpiredBoothRun(principal.boothDeviceId());
        }
        CaptureRun run;
        try {
            run = captureRunRepository.saveAndFlush(CaptureRun.start(principal.participantId(), event.getId(),
                    event.getCurrentExperienceVersionId(), principal.isBooth() ? principal.boothDeviceId() : null,
                    now().plus(properties.captureRunTtl())));
        } catch (DataIntegrityViolationException e) {
            // 기기당 활성 회차 유니크(active_booth_device_id) — 이미 진행 중인 손님이 있다
            throw new BusinessException(BoothErrorCode.RUN_ALREADY_ACTIVE);
        }
        int versionNo = experienceVersionRepository.findById(run.getExperienceVersionId())
                .map(EventExperienceVersion::getVersionNo).orElseThrow();
        return new ParticipantDtos.RunStartResponse(run.getPublicId(), versionNo, run.getStateVersion(), offset(run.getExpiresAt()));
    }

    // 기기의 활성 회차가 시간만 지나 남아 있으면 비운다 — 닫지 않고 떠난 손님 때문에 부스가 멈추지 않게.
    // 아직 유효한 회차면 BOOTH-004
    private void releaseExpiredBoothRun(Long boothDeviceId) {
        captureRunRepository.findByActiveBoothDeviceId(boothDeviceId).ifPresent(existing -> {
            if (existing.activeAt(now())) {
                throw new BusinessException(BoothErrorCode.RUN_ALREADY_ACTIVE);
            }
            existing.expire(now());
            captureRunRepository.flush();
        });
    }

    @Transactional(readOnly = true)
    public FrameVersionResponse offeredFrame(ParticipantPrincipal principal, String eventPublicId, Long frameVersionId,
                                             String captureRunId) {
        requireOwnEvent(principal, eventPublicId);
        CaptureRun run = ownRun(principal, captureRunId);
        if (!offers(run.getExperienceVersionId(), frameVersionId)) {
            throw new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED);
        }
        return frameVersionService.view(frameVersionId);
    }

    // ── 업로드 ──────────────────────

    public UploadIntentResponse createUploadIntent(ParticipantPrincipal principal, String eventPublicId,
                                                   ParticipantDtos.CaptureUploadRequest request) {
        Event event = requireOwnEvent(principal, eventPublicId);
        requireActive(principal);
        CaptureRun run = activeRun(principal, request.captureRunId());
        return assetService.createCaptureInputIntent(event.getPublicId(), event.getId(), run.getPublicId(), run.getId(),
                principal.participantId(), request.slotIndex(), request.contentType(), request.fileSize());
    }

    public AssetService.CompleteResult completeUpload(ParticipantPrincipal principal,
                                                                               String assetPublicId) {
        requireActive(principal);
        return assetService.completeCaptureInput(principal.participantId(), assetPublicId);
    }

    // ── 결과 ──────────────────────

    @Transactional(readOnly = true)
    public ComposeJobResponse job(ParticipantPrincipal principal, Long jobId) {
        return ComposeJobResponse.from(ownJob(principal, jobId));
    }

    @Transactional(readOnly = true)
    public List<ParticipantDtos.MyResult> myResults(ParticipantPrincipal principal, String eventPublicId) {
        Event event = requireOwnEvent(principal, eventPublicId);
        requireByod(principal);
        return composeJobRepository.findAllByParticipantIdAndEventIdOrderByIdDesc(principal.participantId(), event.getId())
                .stream()
                .filter(job -> !job.isDeleted())   // 사진함에서 삭제된 결과는 재방문 목록에도 없다
                .map(job -> new ParticipantDtos.MyResult(job.getId(), job.getStatus(), offset(job.getCreatedAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse download(ParticipantPrincipal principal, Long jobId) {
        requireByod(principal);
        return resultService.downloadForParticipant(ownJob(principal, jobId));
    }

    public ResultTokenResponse newResultAccess(ParticipantPrincipal principal, Long jobId) {
        requireByod(principal);
        ComposeJob job = ownJob(principal, jobId);
        return resultService.issue(job, event(job.getEventId()));
    }

    // ── 관문 ──────────────────────

    Event requireOwnEvent(ParticipantPrincipal principal, String eventPublicId) {
        Event event = event(principal.eventId());
        if (!event.getPublicId().equals(eventPublicId)) {
            // 다른 행사 ID 로 권한을 넓힐 수 없다 — 존재도 알리지 않는다
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        return event;
    }

    void requireActive(ParticipantPrincipal principal) {
        if (currentSession(principal).getStatus() != SessionStatus.ACTIVE) {
            throw new BusinessException(ParticipantErrorCode.CONSENT_REQUIRED);
        }
    }

    void requireCaptureOpen(Event event) {
        if (!event.captureOpen(now())) {
            throw new BusinessException(EventErrorCode.CAPTURE_NOT_OPEN);
        }
    }

    CaptureRun activeRun(ParticipantPrincipal principal, String captureRunId) {
        CaptureRun run = ownRun(principal, captureRunId);
        if (!run.activeAt(now())) {
            throw new BusinessException(ParticipantErrorCode.CAPTURE_RUN_NOT_ACTIVE);
        }
        return run;
    }

    boolean offers(Long experienceVersionId, Long frameVersionId) {
        return eventFrameRepository.findAllByExperienceVersionIdOrderBySortOrderAsc(experienceVersionId).stream()
                .anyMatch(frame -> frame.getFrameVersionId().equals(frameVersionId));
    }

    private CaptureRun ownRun(ParticipantPrincipal principal, String captureRunId) {
        return captureRunRepository.findByPublicId(captureRunId)
                .filter(run -> run.getParticipantId().equals(principal.participantId()))
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.CAPTURE_RUN_NOT_ACTIVE));
    }

    private ComposeJob ownJob(ParticipantPrincipal principal, Long jobId) {
        return composeJobRepository.findById(jobId)
                .filter(job -> principal.participantId().equals(job.getParticipantId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private void requireByod(ParticipantPrincipal principal) {
        if (principal.channel() != ParticipantChannel.BYOD) {
            throw new BusinessException(ParticipantErrorCode.BOOTH_SESSION_FORBIDDEN);
        }
    }

    private ParticipantSession currentSession(ParticipantPrincipal principal) {
        return sessionRepository.findById(principal.sessionId())
                .filter(session -> session.usable(now()))
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.SESSION_INVALID));
    }

    private EventExperienceDocument currentExperience(Event event) {
        return experienceVersionRepository.findById(event.getCurrentExperienceVersionId())
                .map(version -> objectMapper.readValue(version.getSettingsJson(), EventExperienceDocument.class))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private Map<String, ParticipantConsent> latestDecisions(Long participantId) {
        Map<String, ParticipantConsent> latest = new HashMap<>();
        consentRepository.findAllByParticipantIdOrderByIdAsc(participantId)
                .forEach(consent -> latest.put(consent.getItemCode(), consent));
        return latest;
    }

    private static List<EventSettings.ConsentItem> requiredItems(EventExperienceDocument experience) {
        return experience.consentItems().stream().filter(item -> Boolean.TRUE.equals(item.required())).toList();
    }

    private static boolean agreed(ParticipantConsent consent, int version) {
        return consent != null && consent.getDecision() == ConsentDecision.AGREED && consent.getItemVersion() == version;
    }

    private Event event(Long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private ParticipantDtos.RunView runView(CaptureRun run) {
        return new ParticipantDtos.RunView(run.getPublicId(), run.getStateVersion(), offset(run.getExpiresAt()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
