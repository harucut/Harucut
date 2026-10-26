package com.harucut.entitlement.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.enums.EntitlementStatus;
import com.harucut.entitlement.enums.ReservationStatus;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.entitlement.repository.UsageReservationRepository;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 플랫폼 CMS — 계약과 수동 이용권 (ADR-0008 §6-1). 계약은 DRAFT 로 만들고 ACTIVE 로 바꿀 때 이용권을 만든다.
 * ENDED·CANCELLED 는 이용권을 지금 끝낸다(진행 중 예약은 그대로 확정·반환된다). 모든 변경은 감사 로그에 남긴다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ContractService {

    // 허용 전이 — 나머지는 409 ENT-003
    private static final Map<ContractStatus, Set<ContractStatus>> TRANSITIONS = Map.of(
            ContractStatus.DRAFT, EnumSet.of(ContractStatus.ACTIVE, ContractStatus.CANCELLED),
            ContractStatus.ACTIVE, EnumSet.of(ContractStatus.ENDED, ContractStatus.CANCELLED),
            ContractStatus.ENDED, EnumSet.noneOf(ContractStatus.class),
            ContractStatus.CANCELLED, EnumSet.noneOf(ContractStatus.class));

    private final ContractRepository contractRepository;
    private final EntitlementRepository entitlementRepository;
    private final UsageReservationRepository reservationRepository;
    private final EntitlementService entitlementService;
    private final WorkspaceRepository workspaceRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public EntitlementDtos.Contract create(String adminPublicId, EntitlementDtos.ContractRequest request) {
        User admin = user(adminPublicId);
        Workspace workspace = organization(request.workspaceId());
        List<Long> eventIds = request.scope() == EntitlementScope.EVENT ? events(workspace, request.eventIds()) : List.of();
        if (request.paymentEvidence() != null && request.paymentEvidence().kind() == EvidenceKind.PORTONE
                && contractRepository.existsByEvidenceKindAndEvidenceReference(EvidenceKind.PORTONE, request.paymentEvidence().reference())) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이미 다른 계약에 연결된 결제입니다.");
        }
        LocalDateTime starts = local(request.startsAt());
        LocalDateTime ends = local(request.endsAt());
        if (!ends.isAfter(starts)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "계약 종료는 시작보다 뒤여야 합니다.");
        }
        Contract contract = contractRepository.save(new Contract(workspace.getId(), request.contractorName().strip(),
                request.scope(), eventIds, request.amount(), request.currency() == null ? "KRW" : request.currency().toUpperCase(),
                starts, ends, request.paymentEvidence() == null ? null : request.paymentEvidence().kind(),
                request.paymentEvidence() == null ? null : request.paymentEvidence().reference(),
                terms(request.entitlement()), admin.getId()));
        auditLogService.record(admin.getId(), workspace.getId(), null, "CONTRACT_CREATED", "CONTRACT", contract.getPublicId(),
                Map.of("scope", request.scope().name()));
        return view(contract);
    }

    @Transactional(readOnly = true)
    public List<EntitlementDtos.Contract> list() {
        return contractRepository.findAllByOrderByIdDesc(PageRequest.of(0, 200)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public EntitlementDtos.Contract get(String contractPublicId) {
        return view(contractRepository.findByPublicId(contractPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND)));
    }

    public EntitlementDtos.Contract changeStatus(String adminPublicId, String contractPublicId, ContractStatus to) {
        User admin = user(adminPublicId);
        Contract contract = contractRepository.findByPublicId(contractPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (!TRANSITIONS.get(contract.getStatus()).contains(to)) {
            throw new BusinessException(EntitlementErrorCode.INVALID_TRANSITION, contract.getStatus() + " → " + to);
        }
        ContractStatus from = contract.getStatus();
        if (contractRepository.transition(contract.getId(), from, to) != 1) {
            throw new BusinessException(EntitlementErrorCode.INVALID_TRANSITION, "다른 변경이 먼저 반영됐다");
        }
        if (to == ContractStatus.ACTIVE) {
            if (contract.getScope() == EntitlementScope.WORKSPACE) {
                entitlementRepository.save(Entitlement.fromContract(contract, null));
            } else {
                contract.eventIdList().forEach(eventId -> entitlementRepository.save(Entitlement.fromContract(contract, eventId)));
            }
        } else if (from == ContractStatus.ACTIVE) {
            LocalDateTime now = LocalDateTime.now(clock);
            entitlementRepository.findAllByContractId(contract.getId()).forEach(e -> e.endAt(now));
        }
        auditLogService.record(admin.getId(), contract.getWorkspaceId(), null, "CONTRACT_" + to.name(), "CONTRACT",
                contract.getPublicId(), Map.of("from", from.name()));
        return view(contractRepository.findById(contract.getId()).orElseThrow());
    }

    // ── 수동 이용권(ManualGrant) ──────────────────────

    public EntitlementDtos.Entitlement grantManually(String adminPublicId, EntitlementDtos.ManualGrantRequest request) {
        User admin = user(adminPublicId);
        Workspace workspace = organization(request.workspaceId());
        Long eventId = request.eventId() == null ? null : events(workspace, List.of(request.eventId())).getFirst();
        LocalDateTime from = local(request.validFrom());
        LocalDateTime to = local(request.validTo());
        if (!to.isAfter(from)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "유효기간 끝은 시작보다 뒤여야 합니다.");
        }
        Entitlement entitlement = entitlementRepository.save(Entitlement.manual(workspace.getId(), eventId,
                terms(request.terms()), from, to, admin.getId(), request.grantorNote().strip()));
        auditLogService.record(admin.getId(), workspace.getId(), eventId, "ENTITLEMENT_GRANTED", "ENTITLEMENT",
                entitlement.getPublicId(), Map.of("composeQuota", request.terms().composeQuota()));
        return view(entitlement);
    }

    @Transactional(readOnly = true)
    public List<EntitlementDtos.Entitlement> entitlements() {
        return entitlementRepository.findAllByOrderByIdDesc(PageRequest.of(0, 200)).stream().map(this::view).toList();
    }

    /** 회수·만료 전에 보여 줄 영향 — 이 이용권을 쓰는 운영 중 행사와 아직 종결되지 않은 예약 수 */
    @Transactional(readOnly = true)
    public EntitlementDtos.Impact impact(String entitlementPublicId) {
        Entitlement entitlement = entitlement(entitlementPublicId);
        List<Event> live = entitlement.getEventId() != null
                ? eventRepository.findById(entitlement.getEventId()).filter(e -> e.getStatus() == EventStatus.LIVE).stream().toList()
                : eventRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(entitlement.getWorkspaceId()).stream()
                .filter(e -> e.getStatus() == EventStatus.LIVE).toList();
        // 지금 그 행사에 적용되는 이용권이 이것인 행사만 — 행사 범위 이용권에 덮였거나 이미 끝난·회수된 이용권은 영향이 없다
        return new EntitlementDtos.Impact(live.stream()
                .filter(e -> entitlementService.applicable(e).map(Entitlement::getId).filter(entitlement.getId()::equals).isPresent())
                .map(Event::getPublicId).toList(),
                reservationRepository.countByEntitlementIdAndStatus(entitlement.getId(), ReservationStatus.RESERVED));
    }

    public EntitlementDtos.Entitlement revoke(String adminPublicId, String entitlementPublicId, String reason) {
        User admin = user(adminPublicId);
        Entitlement entitlement = entitlement(entitlementPublicId);
        if (entitlement.getStatus() != EntitlementStatus.ACTIVE) {
            throw new BusinessException(EntitlementErrorCode.INVALID_TRANSITION, "이미 회수된 이용권");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (entitlement.isAllocation()) {
            // 배분을 회수하면 그 양을 부모에 돌려준다(부모 → 자식 순서: 부모 UPDATE 가 먼저, 자식은 커밋 때)
            entitlementRepository.deallocate(entitlement.getParentEntitlementId(), entitlement.getComposeQuota());
            entitlement = entitlement(entitlementPublicId);
        }
        entitlement.revoke(now, reason.strip());
        // 기업 총량을 회수하면 그 배분도 함께 끝난다
        entitlementRepository.findAllByParentEntitlementId(entitlement.getId()).stream()
                .filter(child -> child.getStatus() == EntitlementStatus.ACTIVE)
                .forEach(child -> child.revoke(now, "부모 이용권 회수: " + reason.strip()));
        auditLogService.record(admin.getId(), entitlement.getWorkspaceId(), entitlement.getEventId(), "ENTITLEMENT_REVOKED",
                "ENTITLEMENT", entitlement.getPublicId(), Map.of("reason", reason.strip()));
        return view(entitlement);
    }

    // ── 행사 배분(ADR-0009 §5-2) ──────────────────────

    /** 기업 총량(공간 범위·배분이 아닌 활성 이용권)에서 행사 하나에 떼어 준다. 합이 부모 한도를 넘으면 409 ENT-005 */
    public EntitlementDtos.Entitlement allocate(String adminPublicId, String parentPublicId, EntitlementDtos.AllocationRequest request) {
        User admin = user(adminPublicId);
        Entitlement parent = entitlement(parentPublicId);
        if (parent.getStatus() != EntitlementStatus.ACTIVE || parent.getScope() != EntitlementScope.WORKSPACE || parent.isAllocation()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "활성 공간 범위 이용권(기업 총량)에서만 배분할 수 있습니다.");
        }
        Event event = eventRepository.findByPublicId(request.eventId())
                .filter(e -> e.getWorkspaceId().equals(parent.getWorkspaceId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 공간의 행사가 아닙니다."));
        boolean duplicated = entitlementRepository.findAllByParentEntitlementId(parent.getId()).stream()
                .anyMatch(child -> child.getStatus() == EntitlementStatus.ACTIVE && event.getId().equals(child.getEventId()));
        if (duplicated) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 행사에는 이미 배분이 있습니다 — 배분량을 바꾸세요.");
        }
        int quota = request.composeQuota();
        if (entitlementRepository.allocate(parent.getId(), quota) != 1) {
            throw exceeds(parent.getId(), quota);
        }
        Entitlement child = entitlementRepository.save(Entitlement.allocation(entitlementRepository.findById(parent.getId()).orElseThrow(),
                event.getId(), quota, admin.getId()));
        auditLogService.record(admin.getId(), parent.getWorkspaceId(), event.getId(), "ENTITLEMENT_ALLOCATED", "ENTITLEMENT",
                child.getPublicId(), Map.of("parent", parent.getPublicId(), "composeQuota", quota));
        return view(child);
    }

    /** 배분량 바꾸기 — 늘리면 부모 합 검사(ENT-005), 줄이면 이미 사용·예약한 양 아래로는 ENT-006. 순서는 늘 부모 → 자식 */
    public EntitlementDtos.Entitlement resize(String adminPublicId, String childPublicId, EntitlementDtos.AllocationResizeRequest request) {
        User admin = user(adminPublicId);
        Entitlement child = entitlement(childPublicId);
        if (!child.isAllocation() || child.getStatus() != EntitlementStatus.ACTIVE) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "활성 행사 배분만 배분량을 바꿀 수 있습니다.");
        }
        Long parentId = child.getParentEntitlementId();
        int quota = request.composeQuota();
        int delta = quota - child.getComposeQuota();
        // 늘리기: 부모 합을 먼저 원자적으로 늘리고(넘으면 ENT-005) 자식을 바꾼다.
        // 줄이기: 부모 행을 먼저 잠근 뒤 자식을 조건부로 줄이고(사용·예약 아래면 ENT-006) 그다음 부모 합을 줄인다 — 거절된 요청이 흔적을 남기지 않게
        if (delta > 0 && entitlementRepository.allocate(parentId, delta) != 1) {
            throw exceeds(parentId, delta);
        }
        if (delta < 0) {
            entitlementRepository.lockById(parentId);
        }
        if (delta != 0 && entitlementRepository.resize(child.getId(), quota) != 1) {
            Entitlement current = entitlementRepository.findById(child.getId()).orElseThrow();
            throw new BusinessException(EntitlementErrorCode.ALLOCATION_BELOW_USAGE, "사용 " + current.getUsed() + " · 예약 " + current.getReserved(),
                    Map.of("used", current.getUsed(), "reserved", current.getReserved(), "requested", quota));
        }
        if (delta < 0) {
            entitlementRepository.deallocate(parentId, -delta);
        }
        Entitlement resized = entitlement(childPublicId);
        auditLogService.record(admin.getId(), resized.getWorkspaceId(), resized.getEventId(), "ENTITLEMENT_RESIZED", "ENTITLEMENT",
                resized.getPublicId(), Map.of("composeQuota", quota, "delta", delta));
        return view(resized);
    }

    private BusinessException exceeds(Long parentId, int requested) {
        Entitlement parent = entitlementRepository.findById(parentId).orElseThrow();
        return new BusinessException(EntitlementErrorCode.ALLOCATION_EXCEEDS_PARENT,
                "총량 " + parent.getComposeQuota() + " · 배분 " + parent.getAllocatedQuota() + " · 요청 " + requested,
                Map.of("parentQuota", parent.getComposeQuota(), "allocated", parent.getAllocatedQuota(), "requested", requested));
    }

    // ── helpers ──────────────────────

    private Workspace organization(String workspacePublicId) {
        Workspace workspace = workspaceRepository.findByPublicId(workspacePublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (workspace.isPersonal()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "계약·이용권은 조직 공간에만 줍니다.");
        }
        return workspace;
    }

    // 행사 public id → 내부 id. 그 공간의 행사여야 한다
    private List<Long> events(Workspace workspace, List<String> eventPublicIds) {
        if (eventPublicIds == null || eventPublicIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "행사 범위에는 행사가 하나 이상 있어야 합니다.");
        }
        return eventPublicIds.stream().distinct().map(id -> eventRepository.findByPublicId(id)
                        .filter(e -> e.getWorkspaceId().equals(workspace.getId()))
                        .orElseThrow(() -> new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 공간의 행사가 아닙니다: " + id))
                        .getId())
                .toList();
    }

    private static Contract.Terms terms(EntitlementDtos.Terms t) {
        return new Contract.Terms(t.composeQuota(), t.retentionDays(), t.maxBoothDevices(), t.maxLiveEvents(), t.features());
    }

    private static EntitlementDtos.Terms terms(Contract.Terms t) {
        return new EntitlementDtos.Terms(t.composeQuota(), t.retentionDays(), t.maxBoothDevices(), t.maxLiveEvents(), t.features());
    }

    private Entitlement entitlement(String publicId) {
        return entitlementRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private EntitlementDtos.Contract view(Contract c) {
        Map<Long, String> eventIds = eventRepository.findAllById(c.eventIdList()).stream()
                .collect(Collectors.toMap(Event::getId, Event::getPublicId));
        return new EntitlementDtos.Contract(c.getPublicId(), workspacePublicId(c.getWorkspaceId()), c.getContractorName(),
                c.getScope(), c.eventIdList().stream().map(eventIds::get).filter(Objects::nonNull).toList(), c.getAmount(),
                c.getCurrency(), offset(c.getStartsAt()), offset(c.getEndsAt()),
                c.getEvidenceKind() == null ? null : new EntitlementDtos.Evidence(c.getEvidenceKind(), c.getEvidenceReference()),
                offset(c.getPaymentVerifiedAt()), terms(c.terms()), c.getStatus(), offset(c.getCreatedAt()));
    }

    private EntitlementDtos.Entitlement view(Entitlement e) {
        String eventId = e.getEventId() == null ? null : eventRepository.findById(e.getEventId()).map(Event::getPublicId).orElse(null);
        String contractId = e.getContractId() == null ? null
                : contractRepository.findById(e.getContractId()).map(Contract::getPublicId).orElse(null);
        return new EntitlementDtos.Entitlement(e.getPublicId(), e.getScope(), workspacePublicId(e.getWorkspaceId()), eventId,
                e.getSource(), contractId, new EntitlementDtos.Terms(e.getComposeQuota(), e.getRetentionDays(),
                e.getMaxBoothDevices(), e.getMaxLiveEvents(), e.getFeatures()), e.getUsed(), e.getReserved(),
                offset(e.getValidFrom()), offset(e.getValidTo()), e.getStatus(), e.getGrantorNote(), e.getRevokeReason(),
                e.getParentEntitlementId() == null ? null
                        : entitlementRepository.findById(e.getParentEntitlementId()).map(Entitlement::getPublicId).orElse(null),
                e.getAllocatedQuota());
    }

    private String workspacePublicId(Long workspaceId) {
        return workspaceRepository.findById(workspaceId).map(Workspace::getPublicId).orElse(null);
    }

    private User user(String publicId) {
        return userRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
