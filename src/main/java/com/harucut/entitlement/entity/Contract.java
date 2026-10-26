package com.harucut.entitlement.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.entitlement.converter.FeaturesConverter;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.enums.Feature;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 합의한 계약 (ADR-0008 §6-1). ACTIVE 가 되면 이 조건으로 이용권을 만든다. 결제와 분리 — 증빙은 참조만 남긴다
@Entity
@Table(name = "contract", indexes = @Index(name = "idx_contract_workspace_id", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contract extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "contractor_name", nullable = false, length = 100)
    private String contractorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntitlementScope scope;

    @Column(name = "event_ids", length = 500)
    private String eventIds;

    private Long amount;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_kind")
    private EvidenceKind evidenceKind;

    @Column(name = "evidence_reference", length = 200)
    private String evidenceReference;

    @Column(name = "compose_quota", nullable = false)
    private int composeQuota;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Column(name = "max_booth_devices")
    private Integer maxBoothDevices;

    @Column(name = "max_live_events")
    private Integer maxLiveEvents;

    @Convert(converter = FeaturesConverter.class)
    @Column(nullable = false, length = 200)
    private Set<Feature> features = EnumSet.noneOf(Feature.class);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    public Contract(Long workspaceId, String contractorName, EntitlementScope scope, List<Long> eventIds, Long amount,
                    String currency, LocalDateTime startsAt, LocalDateTime endsAt, EvidenceKind evidenceKind,
                    String evidenceReference, Terms terms, Long createdByUserId) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.contractorName = contractorName;
        this.scope = scope;
        this.eventIds = eventIds == null || eventIds.isEmpty() ? null
                : eventIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        this.amount = amount;
        this.currency = currency;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.evidenceKind = evidenceKind;
        this.evidenceReference = evidenceReference;
        this.composeQuota = terms.composeQuota();
        this.retentionDays = terms.retentionDays();
        this.maxBoothDevices = terms.maxBoothDevices();
        this.maxLiveEvents = terms.maxLiveEvents();
        this.features = terms.features() == null || terms.features().isEmpty()
                ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(terms.features());
        this.status = ContractStatus.DRAFT;
        this.createdByUserId = createdByUserId;
    }

    public List<Long> eventIdList() {
        return eventIds == null ? List.of() : Arrays.stream(eventIds.split(",")).map(Long::valueOf).toList();
    }

    public Terms terms() {
        return new Terms(composeQuota, retentionDays, maxBoothDevices, maxLiveEvents, features);
    }

    public void changeStatus(ContractStatus status) {
        this.status = status;
    }

    public void attachEvidence(EvidenceKind kind, String reference) {
        this.evidenceKind = kind;
        this.evidenceReference = reference;
    }

    /** 이용권 조건 묶음 — 계약과 수동 부여가 같은 모양을 쓴다 */
    public record Terms(int composeQuota, Integer retentionDays, Integer maxBoothDevices, Integer maxLiveEvents,
                        Set<Feature> features) {
    }
}
