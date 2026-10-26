package com.harucut.entitlement.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.entitlement.converter.FeaturesConverter;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EntitlementSource;
import com.harucut.entitlement.enums.EntitlementStatus;
import com.harucut.entitlement.enums.Feature;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 지금 쓸 수 있는 행사 혜택 (ADR-0008 §6). used·reserved 는 엔티티로 바꾸지 않는다 — 저장소의 조건부 UPDATE 만이
 * {@code used + reserved <= composeQuota} 를 지키며 바꾼다(동시 접수에서 초과 예약이 나지 않게).
 *
 * <p>{@code @DynamicUpdate}: 회수·종료처럼 엔티티를 고쳐 저장할 때 바뀐 열만 UPDATE 한다 — 전체 열을 쓰면 그사이
 * 커밋된 예약·확정의 used·reserved 를 읽었던 낡은 값으로 되돌린다.
 */
@DynamicUpdate
@Entity
@Table(name = "entitlement", indexes = {
        @Index(name = "idx_entitlement_workspace", columnList = "workspace_id, status"),
        @Index(name = "idx_entitlement_event", columnList = "event_id, status")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entitlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entitlement_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntitlementScope scope;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "event_id")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntitlementSource source;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "compose_quota", nullable = false)
    private int composeQuota;

    @Column(nullable = false)
    private int used;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Column(name = "max_booth_devices")
    private Integer maxBoothDevices;

    @Column(name = "max_live_events")
    private Integer maxLiveEvents;

    @Convert(converter = FeaturesConverter.class)
    @Column(nullable = false, length = 200)
    private Set<Feature> features = EnumSet.noneOf(Feature.class);

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDateTime validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntitlementStatus status;

    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    @Column(name = "grantor_note", length = 500)
    private String grantorNote;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    private Entitlement(EntitlementScope scope, Long workspaceId, Long eventId, EntitlementSource source, Long contractId,
                        Contract.Terms terms, LocalDateTime validFrom, LocalDateTime validTo, Long grantedByUserId,
                        String grantorNote) {
        this.publicId = PublicIds.generate();
        this.scope = scope;
        this.workspaceId = workspaceId;
        this.eventId = eventId;
        this.source = source;
        this.contractId = contractId;
        this.composeQuota = terms.composeQuota();
        this.retentionDays = terms.retentionDays();
        this.maxBoothDevices = terms.maxBoothDevices();
        this.maxLiveEvents = terms.maxLiveEvents();
        this.features = terms.features() == null || terms.features().isEmpty()
                ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(terms.features());
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.status = EntitlementStatus.ACTIVE;
        this.grantedByUserId = grantedByUserId;
        this.grantorNote = grantorNote;
    }

    public static Entitlement fromContract(Contract contract, Long eventId) {
        return new Entitlement(eventId == null ? EntitlementScope.WORKSPACE : EntitlementScope.EVENT,
                contract.getWorkspaceId(), eventId, EntitlementSource.CONTRACT, contract.getId(), contract.terms(),
                contract.getStartsAt(), contract.getEndsAt(), contract.getCreatedByUserId(), null);
    }

    public static Entitlement manual(Long workspaceId, Long eventId, Contract.Terms terms, LocalDateTime validFrom,
                                     LocalDateTime validTo, Long grantedByUserId, String grantorNote) {
        return new Entitlement(eventId == null ? EntitlementScope.WORKSPACE : EntitlementScope.EVENT, workspaceId,
                eventId, EntitlementSource.MANUAL, null, terms, validFrom, validTo, grantedByUserId, grantorNote);
    }

    public boolean activeAt(LocalDateTime now) {
        return status == EntitlementStatus.ACTIVE && !now.isBefore(validFrom) && now.isBefore(validTo);
    }

    public int remaining() {
        return Math.max(0, composeQuota - used - reserved);
    }

    public boolean allows(Feature feature) {
        return features.contains(feature);
    }

    public void revoke(LocalDateTime now, String reason) {
        this.status = EntitlementStatus.REVOKED;
        this.revokedAt = now;
        this.revokeReason = reason;
    }

    /** 계약이 끝나면 지금부터 쓸 수 없게 — 진행 중 예약은 그대로 확정·반환된다 */
    public void endAt(LocalDateTime now) {
        if (now.isBefore(validTo)) {
            this.validTo = now;
        }
    }
}
