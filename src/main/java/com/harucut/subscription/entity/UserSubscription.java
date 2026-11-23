package com.harucut.subscription.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_subscription",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_subscription_user_id",
                columnNames = "user_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSubscription extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_subscription_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanTier planTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus subscriptionStatus;

    private LocalDateTime currentPeriodStart;

    private LocalDateTime currentPeriodEnd;

    @Column(nullable = false)
    private boolean autoRenew;

    @Version
    private Long version;

    @Column(name = "reserved_user_coupon_id")
    private Long reservedUserCouponId;

    // 유료 등급의 출처 — BASIC·만료면 null. 웹만 우리 갱신 배치가 청구한다(ADR-0011 §2)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SubscriptionSource source;

    private UserSubscription(Long userId, PlanTier planTier, SubscriptionStatus subscriptionStatus,
                             LocalDateTime currentPeriodStart, LocalDateTime currentPeriodEnd, boolean autoRenew) {
        this.userId = userId;
        this.planTier = planTier;
        this.subscriptionStatus = subscriptionStatus;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.autoRenew = autoRenew;
    }

    // 가입 직후 기본 구독. BASIC은 결제 주기가 없으므로 기간 null, 갱신할 것도 없으므로 autoRenew=false
    public static UserSubscription createBasic(Long userId) {
        return new UserSubscription(
                userId,
                PlanTier.BASIC,
                SubscriptionStatus.ACTIVE,
                null,
                null,
                false
        );
    }

    // ── 상태 전이. 가드(해지 가능 여부 등)는 서비스 몫이고 여기는 무조건 전이만 한다 ──
    public void activatePaid(PlanTier planTier, LocalDateTime start, LocalDateTime end) {
        this.source = SubscriptionSource.WEB;
        this.planTier = planTier;
        this.subscriptionStatus = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = start;
        this.currentPeriodEnd = end;
        this.autoRenew = true;
    }

    public void renew(LocalDateTime start, LocalDateTime end) {
        this.subscriptionStatus = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = start;
        this.currentPeriodEnd = end;
    }

    public void markPastDue() {
        this.subscriptionStatus = SubscriptionStatus.PAST_DUE;
    }

    // tier와 주기는 건드리지 않는다 — 이미 결제한 주기는 currentPeriodEnd까지 유료 등급 유지(환불 없음)
    public void cancelAutoRenew() {
        this.autoRenew = false;
        this.subscriptionStatus = SubscriptionStatus.CANCELED;
    }

    // BASIC의 불변식(주기 null)을 복원한다 — effectiveTier의 첫 분기가 이 불변식에 기댄다
    public void expireToFree() {
        this.source = null;
        this.planTier = PlanTier.BASIC;
        this.subscriptionStatus = SubscriptionStatus.EXPIRED;
        this.currentPeriodStart = null;
        this.currentPeriodEnd = null;
        this.autoRenew = false;
    }

    // 쿠폰 무료 부여 — 카드가 없으므로 autoRenew=false
    public void activateGrant(PlanTier planTier, LocalDateTime start, LocalDateTime end) {
        this.source = SubscriptionSource.COUPON;
        this.planTier = planTier;
        this.subscriptionStatus = SubscriptionStatus.GRANTED;
        this.currentPeriodStart = start;
        this.currentPeriodEnd = end;
        this.autoRenew = false;
    }

    // 스토어 구독 — 결제·갱신은 스토어가 한다. 상태는 스토어 구매의 상태를 그대로 옮긴다(자동갱신 끔 = CANCELED, 결제 재시도 = PAST_DUE)
    public void activateStore(SubscriptionSource source, PlanTier planTier, SubscriptionStatus status, LocalDateTime start,
                              LocalDateTime end, boolean autoRenew) {
        if (this.source != source || this.currentPeriodStart == null) {
            this.currentPeriodStart = start;
        }
        this.source = source;
        this.planTier = planTier;
        this.subscriptionStatus = status;
        this.currentPeriodEnd = end;
        this.autoRenew = autoRenew;
    }

    /** 지금 유료 등급을 주고 있는 출처 — 기한이 지났으면(읽기 시점 강등) 없다 */
    public SubscriptionSource activeSourceAt(LocalDateTime now) {
        return effectiveTier(now) == PlanTier.BASIC ? null : source;
    }

    // 모든 정책 판정의 기준. DB의 planTier를 그대로 믿지 않는다 —
    // 만료 배치가 아직 강등하지 못한 공백기를 읽기 시점에 보수적으로 BASIC 취급해 메운다
    public PlanTier effectiveTier(LocalDateTime now) {
        if (planTier == PlanTier.BASIC) {
            return PlanTier.BASIC;
        }
        if (currentPeriodEnd == null) {
            return planTier;
        }
        // 경계 포함: now == currentPeriodEnd 도 만료로 본다
        return now.isBefore(currentPeriodEnd) ? planTier : PlanTier.BASIC;
    }

    public void reserveGrant(Long userCouponId) {
        this.reservedUserCouponId = userCouponId;
    }

    public void clearReservedGrant() {
        this.reservedUserCouponId = null;
    }
}
