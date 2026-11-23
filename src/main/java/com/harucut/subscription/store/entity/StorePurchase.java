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

    // 주인 없음(null) = 앱이 등록하기 전에 스토어 알림이 먼저 온 구매. 등록할 때 그 행을 이어받는다
    @Column(name = "user_id")
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

    // 마지막으로 반영한 입력의 서명 시각 — 이보다 오래된 입력은 상태·기한·자동갱신을 바꾸지 않는다(ADR-0011 §2-4)
    @Column(name = "last_event_at", nullable = false)
    private LocalDateTime lastEventAt;

    public static String hash(String storeKey) {
        return OpaqueTokens.hash(storeKey);
    }

    /**
     * 반영 규칙(ADR-0011 §2-4) — **기간 먼저, 서명 시각은 같은 기간 안에서만** ((expiresAt, signedAt) 사전순).
     * <ul>
     *   <li>저장 기간보다 늦게 끝나는 거래 = 새 기간: 서명 시각과 상관없이 반영하고, 이 기간의 마지막 이벤트 시각을 그 서명 시각으로 다시 잡는다</li>
     *   <li>같은 기간: 마지막 이벤트 시각보다 나중에 서명된 것만 상태·자동갱신·환불을 바꾼다 — 늦게 온 옛 알림·옛 증빙은 되돌리지 못한다</li>
     *   <li>이전 기간(저장 기간보다 먼저 끝남): 지금 기간과 무관하다 — 이전 기간의 환불도 지금 기간을 건드리지 않는다</li>
     * </ul>
     *
     * @param tier 이 입력의 상품 등급(모르는 상품이면 호출자가 저장값을 넘긴다)
     * @return 반영했으면 true
     */
    public boolean applyIfNewer(VerifiedPurchase verified, PlanTier tier, LocalDateTime now) {
        boolean newPeriod = verified.expiresAt().isAfter(expiresAt);
        boolean samePeriodNewer = verified.expiresAt().isEqual(expiresAt) && verified.signedAt().isAfter(lastEventAt);
        if (!newPeriod && !samePeriodNewer) {
            return false;
        }
        lastEventAt = verified.signedAt();
        lastVerifiedAt = now;
        productId = verified.productId();
        planTier = tier;
        expiresAt = verified.expiresAt();
        autoRenew = verified.autoRenew();
        environment = verified.environment();
        status = verified.statusAt(now);
        return true;
    }

    /** 주인 없는 행(알림이 먼저 온 구매)을 이 계정이 이어받는다. 이미 주인이 있으면 false */
    public boolean claim(Long ownerUserId) {
        if (userId != null) {
            return userId.equals(ownerUserId);
        }
        userId = ownerUserId;
        return true;
    }

    /** 지금 등급을 줄 수 있는가 — 환불·만료가 아니고 기한 안 */
    public boolean grantsAt(LocalDateTime now) {
        return status != StorePurchaseStatus.EXPIRED && status != StorePurchaseStatus.REVOKED && now.isBefore(expiresAt);
    }
}
