package com.harucut.subscription.store.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.store.StoreEnvironment;
import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.StorePurchaseStatus;
import com.harucut.subscription.store.verifier.VerifiedPurchase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 스토어 구매 하나(ADR-0011 §2) — 애플 원거래·구글 구매 토큰. 한 구매는 한 계정에만 묶인다(유니크 platform+key 해시). 행은 묶을 때 INSERT IGNORE 로 만든다 */
@Entity
@Table(name = "store_purchase",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_purchase_key", columnNames = {"platform", "store_key_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorePurchase extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_purchase_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StorePlatform platform;

    @Column(name = "store_key", nullable = false, length = 512)
    private String storeKey;

    @Column(name = "store_key_hash", nullable = false, length = 64)
    private String storeKeyHash;

    @Column(name = "product_id", nullable = false, length = 150)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", nullable = false)
    private PlanTier planTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StorePurchaseStatus status;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StoreEnvironment environment;

    @Column(name = "last_verified_at", nullable = false)
    private LocalDateTime lastVerifiedAt;

    public static String hash(String storeKey) {
        return OpaqueTokens.hash(storeKey);
    }

    /** 검증된 최신 상태로 — 상품이 바뀌었으면(업·다운그레이드) 등급도 */
    public void apply(VerifiedPurchase verified, PlanTier planTier, LocalDateTime now) {
        this.productId = verified.productId();
        this.planTier = planTier;
        this.expiresAt = verified.expiresAt();
        this.autoRenew = verified.autoRenew();
        this.environment = verified.environment();
        this.status = verified.statusAt(now);
        this.lastVerifiedAt = now;
    }

    /** 스토어 알림이 상태만 알려 줄 때(자동갱신 끔·결제 재시도·환불) */
    public void mark(StorePurchaseStatus status, boolean autoRenew, LocalDateTime now) {
        this.status = status;
        this.autoRenew = autoRenew;
        this.lastVerifiedAt = now;
    }

    /** 지금 등급을 줄 수 있는가 — 환불·만료가 아니고 기한 안 */
    public boolean grantsAt(LocalDateTime now) {
        return status != StorePurchaseStatus.EXPIRED && status != StorePurchaseStatus.REVOKED && now.isBefore(expiresAt);
    }
}
