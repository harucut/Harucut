package com.harucut.subscription.service;

import com.harucut.coupon.service.GrantActivationService;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.config.PaymentProperties;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.store.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SubscriptionExpirationService {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final BillingKeyRepository billingKeyRepository;
    private final GrantActivationService grantActivationService;
    private final PaymentProperties paymentProperties;
    private final StoreProperties storeProperties;

    @Transactional
    public void expire(Long subscriptionId, LocalDateTime baseTime) {
        // 리더가 고른 뒤 갱신(웹 결제·스토어 알림)이 커밋됐을 수 있다 — 행을 잠그고 지금도 만료 대상인지 다시 본다(리더와 같은 조건)
        UserSubscription subscription = userSubscriptionRepository.lockById(subscriptionId).orElse(null);
        if (subscription == null || !isExpirable(subscription, baseTime)) {
            return;
        }

        if (subscription.getReservedUserCouponId() != null) {
            grantActivationService.activate(subscription, baseTime);
            return;
        }

        subscription.expireToFree();
        billingKeyRepository.findAllByUserIdAndStatus(subscription.getUserId(), BillingKeyStatus.ACTIVE)
                .forEach(BillingKey::delete);
    }

    // SubscriptionExpirationJobConfig 의 리더 조건과 같다 — 둘 중 하나만 바꾸면 고르고도 건너뛰거나 재확인 없이 강등한다
    boolean isExpirable(UserSubscription subscription, LocalDateTime baseTime) {
        LocalDateTime end = subscription.getCurrentPeriodEnd();
        if (end == null) {
            return false;
        }
        SubscriptionStatus status = subscription.getSubscriptionStatus();
        SubscriptionSource source = subscription.getSource();
        boolean store = source == SubscriptionSource.APP_STORE || source == SubscriptionSource.PLAY_STORE;
        if ((status == SubscriptionStatus.CANCELED || status == SubscriptionStatus.GRANTED) && !end.isAfter(baseTime)) {
            return true;
        }
        if (status == SubscriptionStatus.PAST_DUE && (source == null || source == SubscriptionSource.WEB)
                && !end.isAfter(baseTime.minusDays(paymentProperties.graceDays()))) {
            return true;
        }
        return store && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE)
                && !end.isAfter(baseTime.minusDays(storeProperties.graceDays()));
    }
}
