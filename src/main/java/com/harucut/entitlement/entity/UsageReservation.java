package com.harucut.entitlement.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.entitlement.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 합성 작업 하나의 사용량 예약. RESERVED → COMMITTED | RELEASED 는 저장소의 조건부 UPDATE 로 한 번만 바뀐다
@Entity
// 작업 하나가 이용권마다 한 행 — 행사 배분이면 부모(기업 총량)와 자식(행사) 두 행(ADR-0009 §5)
@Table(name = "usage_reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_reservation_job_entitlement", columnNames = {"compose_job_id", "entitlement_id"}),
        indexes = @Index(name = "idx_usage_reservation_entitlement", columnList = "entitlement_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_reservation_id")
    private Long id;

    @Column(name = "compose_job_id", nullable = false)
    private Long composeJobId;

    @Column(name = "entitlement_id", nullable = false)
    private Long entitlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    public UsageReservation(Long composeJobId, Long entitlementId) {
        this.composeJobId = composeJobId;
        this.entitlementId = entitlementId;
        this.status = ReservationStatus.RESERVED;
    }
}
