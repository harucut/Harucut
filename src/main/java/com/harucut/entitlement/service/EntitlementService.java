package com.harucut.entitlement.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.entity.UsageLedger;
import com.harucut.entitlement.entity.UsageReservation;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EntitlementStatus;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.enums.LedgerKind;
import com.harucut.entitlement.enums.ReservationStatus;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.entitlement.repository.UsageLedgerRepository;
import com.harucut.entitlement.repository.UsageReservationRepository;
import com.harucut.event.entity.Event;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 행사에 적용되는 이용권 판정과 합성 사용량 예약·확정·반환 (ADR-0008 §6-2).
 *
 * <p>적용 이용권 = 유효기간 안의 <b>행사 범위 > 공간 범위</b> 중 하나(같으면 validTo 가 늦은 것).
 * 예약은 합성 접수와 같은 트랜잭션, 확정·반환은 결과 전이를 이긴 트랜잭션 안에서 — 예약 행의 조건부 UPDATE 가
 * COMMIT·RELEASE 중 하나만 허용하므로 늦게 온 통지가 종결된 예약을 되살리지 않는다. 호출자 트랜잭션에 참여한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class EntitlementService {

    private final EntitlementRepository entitlementRepository;
    private final UsageReservationRepository reservationRepository;
    private final UsageLedgerRepository ledgerRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<Entitlement> applicable(Event event) {
        LocalDateTime now = now();
        return entitlementRepository.findCandidates(event.getWorkspaceId(), event.getId(), EntitlementStatus.ACTIVE).stream()
                .filter(e -> e.activeAt(now))
                .max(Comparator.comparing((Entitlement e) -> e.getScope() == EntitlementScope.EVENT)
                        .thenComparing(Entitlement::getValidTo));
    }

    /** 게시 관문 — 적용 이용권이 없으면 403 ENT-001 */
    @Transactional(readOnly = true)
    public Entitlement requireApplicable(Event event) {
        return applicable(event).orElseThrow(() -> new BusinessException(EntitlementErrorCode.NO_ENTITLEMENT));
    }

    @Transactional(readOnly = true)
    public boolean allows(Event event, Feature feature) {
        return applicable(event).filter(e -> e.allows(feature)).isPresent();
    }

    /** 계약 기능 관문 — 없으면 403 ENT-004 */
    @Transactional(readOnly = true)
    public void requireFeature(Event event, Feature feature) {
        if (!allows(event, feature)) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_ENTITLED, feature.name());
        }
    }

    /**
     * 참가 화면의 관문(회차 시작) — 찍고 나서 막히지 않게 미리 본다. 적용 이용권이 없거나(만료 포함) 남은 한도가 0 이면
     * 409 ENT-002 — 참가자에게는 「오늘 촬영이 마감됐다」 하나로 보인다. 접수에서 예약으로 다시 확인한다.
     */
    @Transactional(readOnly = true)
    public void requireRemaining(Event event) {
        Entitlement entitlement = applicable(event).orElseThrow(() -> new BusinessException(EntitlementErrorCode.QUOTA_REACHED));
        // 행사 배분이면 기업 총량(부모)도 남아 있어야 한다 — 어느 쪽이 막혀도 같은 ENT-002
        if (chain(entitlement).stream().anyMatch(e -> e.remaining() <= 0)) {
            throw new BusinessException(EntitlementErrorCode.QUOTA_REACHED);
        }
    }

    // 예약·확인 순서 — 부모(기업 총량) → 자식(행사 배분). 잠금 순서가 늘 같아 둘 사이 교착이 없다
    private List<Entitlement> chain(Entitlement entitlement) {
        if (!entitlement.isAllocation()) {
            return List.of(entitlement);
        }
        Entitlement parent = entitlementRepository.findById(entitlement.getParentEntitlementId()).orElseThrow();
        return List.of(parent, entitlement);
    }

    /** 새 행사 합성 작업의 예약 1건 — 작업 저장과 같은 트랜잭션. 한도가 없으면 409 ENT-002(트랜잭션 전체가 롤백된다) */
    public void reserve(Long composeJobId, Event event) {
        Entitlement applied = applicable(event)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.QUOTA_REACHED));
        // 행사 배분이면 부모 → 자식 순서로 둘 다. 하나라도 0 행이면 ENT-002 로 트랜잭션 전체가 롤백된다(부모만 차감되는 일 없음)
        for (Entitlement entitlement : chain(applied)) {
            if (entitlementRepository.reserve(entitlement.getId(), now()) != 1) {
                throw new BusinessException(EntitlementErrorCode.QUOTA_REACHED);
            }
            reservationRepository.save(new UsageReservation(composeJobId, entitlement.getId()));
            ledgerRepository.save(new UsageLedger(now(), entitlement.getId(), composeJobId, LedgerKind.RESERVE, 1));
        }
    }

    /** 성공 확정 — 결과 전이를 이긴 트랜잭션에서만 부른다. 예약이 없는 작업(이용권 전 행사·개인 합성)은 아무것도 하지 않는다 */
    public void commit(Long composeJobId) {
        settle(composeJobId, ReservationStatus.COMMITTED);
    }

    /** 최종 실패 반환 — 돈의 환불이 아니라 예약 수량을 돌려주는 것이다 */
    public void release(Long composeJobId) {
        settle(composeJobId, ReservationStatus.RELEASED);
    }

    private void settle(Long composeJobId, ReservationStatus to) {
        // 작업의 예약 전부(부모·자식)를 한 번에 — 한 범위만 확정되는 일이 없다
        if (reservationRepository.settle(composeJobId, to) == 0) {
            return;   // 예약이 없거나 이미 종결됐다
        }
        List<UsageReservation> reservations = reservationRepository.findAllByComposeJobId(composeJobId).stream()
                .sorted(Comparator.comparing(UsageReservation::getId))   // 예약한 순서(부모 → 자식) 그대로
                .toList();
        for (UsageReservation reservation : reservations) {
            if (to == ReservationStatus.COMMITTED) {
                entitlementRepository.commit(reservation.getEntitlementId());
                recordAlerts(reservation.getEntitlementId());
            } else {
                entitlementRepository.release(reservation.getEntitlementId());
            }
            ledgerRepository.save(new UsageLedger(now(), reservation.getEntitlementId(), composeJobId,
                    to == ReservationStatus.COMMITTED ? LedgerKind.COMMIT : LedgerKind.RELEASE, 1));
        }
    }

    // 확정 사용량이 80·95·100% 를 처음 넘으면 그 시각을 남기고 감사 로그(ADR-0009 §4-1). 메일은 보내지 않는다
    private void recordAlerts(Long entitlementId) {
        LocalDateTime now = now();
        List<Integer> reached = new ArrayList<>();
        if (entitlementRepository.markAlert80(entitlementId, now) == 1) {
            reached.add(80);
        }
        if (entitlementRepository.markAlert95(entitlementId, now) == 1) {
            reached.add(95);
        }
        if (entitlementRepository.markAlert100(entitlementId, now) == 1) {
            reached.add(100);
        }
        if (reached.isEmpty()) {
            return;
        }
        Entitlement entitlement = entitlementRepository.findById(entitlementId).orElseThrow();
        reached.forEach(threshold -> auditLogService.record(null, entitlement.getWorkspaceId(), entitlement.getEventId(),
                "ENTITLEMENT_USAGE_ALERT", "ENTITLEMENT", entitlement.getPublicId(),
                Map.of("threshold", threshold, "used", entitlement.getUsed(), "composeQuota", entitlement.getComposeQuota())));
    }

    /** @param includeParent 공간 OWNER·ADMIN 일 때만 true — 기업 총량(parent)은 운영 담당·행사 고객에게 보이지 않는다 */
    @Transactional(readOnly = true)
    public EntitlementDtos.EventUsage usage(Event event, boolean includeParent) {
        return applicable(event)
                .map(e -> {
                    EntitlementDtos.EventUsage.Parent parent = !includeParent || !e.isAllocation() ? null
                            : entitlementRepository.findById(e.getParentEntitlementId())
                            .map(p -> new EntitlementDtos.EventUsage.Parent(p.getComposeQuota(), p.getUsed(), p.getReserved(), p.remaining()))
                            .orElse(null);
                    return new EntitlementDtos.EventUsage(
                            new EntitlementDtos.EventUsage.Applied(e.getScope(), offset(e.getValidFrom()), offset(e.getValidTo()),
                                    e.getFeatures()),
                            e.getComposeQuota(), e.getUsed(), e.getReserved(), e.remaining(), e.getRetentionDays(),
                            e.getMaxBoothDevices(), alerts(e), parent);
                })
                .orElseGet(() -> new EntitlementDtos.EventUsage(null, 0, 0, 0, 0, null, null, List.of(), null));
    }

    @Transactional(readOnly = true)
    public EntitlementDtos.EventUsage usage(Event event) {
        return usage(event, false);
    }

    // 넘은 것만, 비율 오름차순
    private List<EntitlementDtos.EventUsage.Alert> alerts(Entitlement e) {
        List<EntitlementDtos.EventUsage.Alert> alerts = new ArrayList<>();
        if (e.getNotified80At() != null) {
            alerts.add(new EntitlementDtos.EventUsage.Alert(80, offset(e.getNotified80At())));
        }
        if (e.getNotified95At() != null) {
            alerts.add(new EntitlementDtos.EventUsage.Alert(95, offset(e.getNotified95At())));
        }
        if (e.getNotified100At() != null) {
            alerts.add(new EntitlementDtos.EventUsage.Alert(100, offset(e.getNotified100At())));
        }
        return alerts;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
