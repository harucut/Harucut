package com.harucut.entitlement.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
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
    private final WorkspaceRepository workspaceRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public EntitlementDtos.Contract create(String adminPublicId, EntitlementDtos.ContractRequest request) {
        User admin = user(adminPublicId);
        Workspace workspace = organization(request.workspaceId());
        List<Long> eventIds = request.scope() == EntitlementScope.EVENT ? events(workspace, request.eventIds()) : List.of();
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

    public EntitlementDtos.Contract changeStatus(String adminPublicId, String contractPublicId, ContractStatus to) {
        User admin = user(adminPublicId);
        Contract contract = contractRepository.findByPublicId(contractPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (!TRANSITIONS.get(contract.getStatus()).contains(to)) {
            throw new BusinessException(EntitlementErrorCode.INVALID_TRANSITION, contract.getStatus() + " → " + to);
        }
        ContractStatus from = contract.getStatus();
        contract.changeStatus(to);
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
        return view(contract);
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
        return new EntitlementDtos.Impact(live.stream().map(Event::getPublicId).toList(),
                reservationRepository.countByEntitlementIdAndStatus(entitlement.getId(), ReservationStatus.RESERVED));
    }

    public EntitlementDtos.Entitlement revoke(String adminPublicId, String entitlementPublicId, String reason) {
        User admin = user(adminPublicId);
        Entitlement entitlement = entitlement(entitlementPublicId);
        if (entitlement.getStatus() != EntitlementStatus.ACTIVE) {
            throw new BusinessException(EntitlementErrorCode.INVALID_TRANSITION, "이미 회수된 이용권");
        }
        entitlement.revoke(LocalDateTime.now(clock), reason.strip());
        auditLogService.record(admin.getId(), entitlement.getWorkspaceId(), entitlement.getEventId(), "ENTITLEMENT_REVOKED",
                "ENTITLEMENT", entitlement.getPublicId(), Map.of("reason", reason.strip()));
        return view(entitlement);
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
                terms(c.terms()), c.getStatus(), offset(c.getCreatedAt()));
    }

    private EntitlementDtos.Entitlement view(Entitlement e) {
        String eventId = e.getEventId() == null ? null : eventRepository.findById(e.getEventId()).map(Event::getPublicId).orElse(null);
        String contractId = e.getContractId() == null ? null
                : contractRepository.findById(e.getContractId()).map(Contract::getPublicId).orElse(null);
        return new EntitlementDtos.Entitlement(e.getPublicId(), e.getScope(), workspacePublicId(e.getWorkspaceId()), eventId,
                e.getSource(), contractId, new EntitlementDtos.Terms(e.getComposeQuota(), e.getRetentionDays(),
                e.getMaxBoothDevices(), e.getMaxLiveEvents(), e.getFeatures()), e.getUsed(), e.getReserved(),
                offset(e.getValidFrom()), offset(e.getValidTo()), e.getStatus(), e.getGrantorNote(), e.getRevokeReason());
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
