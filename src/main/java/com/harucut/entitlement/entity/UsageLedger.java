package com.harucut.entitlement.entity;

import com.harucut.entitlement.enums.LedgerKind;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 사용량 이력 — 감사용. (작업, 종류) 유니크라 같은 확정이 두 번 남지 않는다
@Entity
@Table(name = "usage_ledger",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_ledger_job_kind", columnNames = {"compose_job_id", "kind"}),
        indexes = @Index(name = "idx_usage_ledger_entitlement", columnList = "entitlement_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_ledger_id")
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "entitlement_id", nullable = false)
    private Long entitlementId;

    @Column(name = "compose_job_id", nullable = false)
    private Long composeJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerKind kind;

    @Column(nullable = false)
    private int quantity;

    public UsageLedger(LocalDateTime createdAt, Long entitlementId, Long composeJobId, LedgerKind kind, int quantity) {
        this.createdAt = createdAt;
        this.entitlementId = entitlementId;
        this.composeJobId = composeJobId;
        this.kind = kind;
        this.quantity = quantity;
    }
}
