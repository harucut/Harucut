package com.harucut.booth.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.booth.dto.BoothDtos;
import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.entity.BoothPairing;
import com.harucut.booth.enums.BoothDeviceStatus;
import com.harucut.booth.enums.PairingStatus;
import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.booth.repository.BoothPairingRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.event.service.EventService;
import com.harucut.participant.config.ParticipationProperties;
import com.harucut.participant.repository.CaptureRunRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 운영자의 부스 기기 관리 (ADR-0007 §4) — 등록·연결 코드 발급·회수. 권한은 행사 공간의 OWNER·ADMIN.
 *
 * <p>연결 코드는 base32 8자 {@code XXXX-XXXX}. 앞 4자는 거래를 찾는 lookup(비밀 아님), 뒤 4자가 비밀이다.
 * 열린 거래끼리는 lookup 이 겹치지 않게 발급하고, 비밀은 해시로만 남긴다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BoothDeviceService {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LOOKUP_ATTEMPTS = 20;

    private final BoothDeviceRepository deviceRepository;
    private final BoothPairingRepository pairingRepository;
    private final CaptureRunRepository captureRunRepository;
    private final ParticipantSessionRepository sessionRepository;
    private final EventService eventService;
    private final EntitlementService entitlementService;
    private final WorkspaceRepository workspaceRepository;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final ParticipationProperties properties;
    private final Clock clock;

    public BoothDtos.DeviceResponse create(String userPublicId, String eventPublicId, String name) {
        Event event = eventService.requireOperable(userPublicId, eventPublicId);
        // 적용 이용권의 부스 수가 설정값을 대신한다(ADR-0008 §6-2)
        int limit = entitlementService.applicable(event).map(e -> e.getMaxBoothDevices())
                .filter(Objects::nonNull).orElse(properties.maxBoothDevicesPerEvent());
        // 동시에 두 대를 만들어도 상한을 넘지 않게 공간 행을 잠근 뒤 잠금 읽기로 센다
        workspaceRepository.lockById(event.getWorkspaceId());
        if (deviceRepository.lockUnrevokedIds(event.getId()).size() >= limit) {
            throw new BusinessException(BoothErrorCode.DEVICE_LIMIT);
        }
        BoothDevice device = deviceRepository.save(BoothDevice.pending(event.getWorkspaceId(), event.getId(), name.strip()));
        audit(userPublicId, device, "BOOTH_DEVICE_CREATED");
        return withNewCode(device);
    }

    @Transactional(readOnly = true)
    public List<BoothDtos.DeviceResponse> list(String userPublicId, String eventPublicId) {
        Event event = eventService.requireOperable(userPublicId, eventPublicId);
        return deviceRepository.findAllByEventIdOrderByIdAsc(event.getId()).stream()
                .map(device -> BoothDtos.DeviceResponse.of(device, null, null, offset(device.getLastSeenAt())))
                .toList();
    }

    /** 연결 코드 재발급 — 이전 코드는 닫힌다. 이미 연결된 기기의 토큰은 새 코드가 교환될 때 무효가 된다(기기 교체) */
    public BoothDtos.DeviceResponse reissueCode(String userPublicId, String devicePublicId) {
        BoothDevice device = operableDevice(userPublicId, devicePublicId);
        if (device.isRevoked()) {
            throw new BusinessException(BoothErrorCode.DEVICE_UNAVAILABLE);
        }
        audit(userPublicId, device, "BOOTH_CODE_REISSUED");
        return withNewCode(device);
    }

    /** 회수 — 토큰 무효, 열린 코드 닫기, 진행 중 회차 종료, 그 기기로 만든 참가자 세션 회수 */
    public BoothDtos.DeviceResponse revoke(String userPublicId, String devicePublicId) {
        BoothDevice device = operableDevice(userPublicId, devicePublicId);
        LocalDateTime now = now();
        device.revoke();
        pairingRepository.findAllByBoothDeviceIdAndStatus(device.getId(), PairingStatus.OPEN).forEach(BoothPairing::close);
        captureRunRepository.findByActiveBoothDeviceId(device.getId()).ifPresent(run -> run.close(now));
        sessionRepository.revokeAllByBoothDeviceId(device.getId());
        audit(userPublicId, device, "BOOTH_DEVICE_REVOKED");
        return BoothDtos.DeviceResponse.of(device, null, null, offset(device.getLastSeenAt()));
    }

    private void audit(String userPublicId, BoothDevice device, String action) {
        Long actorId = userRepository.findByPublicId(userPublicId).map(User::getId).orElse(null);
        auditLogService.record(actorId, device.getWorkspaceId(), device.getEventId(), action, "BOOTH_DEVICE",
                device.getPublicId(), null);
    }

    private BoothDevice operableDevice(String userPublicId, String devicePublicId) {
        BoothDevice device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        eventService.requireOperable(userPublicId, device.getEventId());
        return device;
    }

    private BoothDtos.DeviceResponse withNewCode(BoothDevice device) {
        pairingRepository.findAllByBoothDeviceIdAndStatus(device.getId(), PairingStatus.OPEN).forEach(BoothPairing::close);
        String lookup = freeLookup();
        String secret = random(4);
        LocalDateTime expiresAt = now().plus(properties.pairingCodeTtl());
        pairingRepository.save(new BoothPairing(device.getId(), lookup, OpaqueTokens.hash(secret), expiresAt));
        return BoothDtos.DeviceResponse.of(device, lookup + "-" + secret, offset(expiresAt), offset(device.getLastSeenAt()));
    }

    // 열린 거래와 겹치지 않는 lookup. 32^4 ≈ 100만 칸에 열린 거래는 많아야 수백이라 거의 첫 시도에 끝난다
    private String freeLookup() {
        for (int i = 0; i < LOOKUP_ATTEMPTS; i++) {
            String lookup = random(4);
            if (!pairingRepository.existsByLookupAndStatus(lookup, PairingStatus.OPEN)) {
                return lookup;
            }
        }
        throw new IllegalStateException("연결 코드 lookup 을 고르지 못했다");
    }

    private static String random(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
