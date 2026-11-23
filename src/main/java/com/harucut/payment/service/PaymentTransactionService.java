package com.harucut.payment.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.Payment;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.gateway.dto.BillingKeyResult;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.payment.repository.PaymentRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final BillingKeyRepository billingKeyRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final Clock clock;

    public record CreatedOrder(Long orderId, String orderPublicId, String billingKeyValue) {}

    /** supersededBy: 청구하는 사이 스토어로 구독했다 — 결제는 기록했지만 구독은 스토어 그대로(환불 확인 필요) */
    public record ChargeApplyResult(boolean success, UserSubscription subscription, SubscriptionSource supersededBy) {
        public static ChargeApplyResult ok(UserSubscription subscription) {
            return new ChargeApplyResult(true, subscription, null);
        }

        public static ChargeApplyResult failed() {
            return new ChargeApplyResult(false, null, null);
        }

        public static ChargeApplyResult superseded(UserSubscription subscription) {
            return new ChargeApplyResult(true, subscription, subscription.getSource());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreatedOrder createInitialOrder(Long userId, PlanTier planTier, int amount,
                                           String idempotencyKey, PgProvider provider,
                                           BillingKeyResult issued) {
        // 스토어 등록과 줄을 세운다 — 구독 행 → 빌링키 → 주문 행(ADR-0011 §2). 출처 검사 뒤 스토어로 구독했으면 청구하지 않는다
        userSubscriptionRepository.lockByUserId(userId)
                .filter(subscription -> isStore(subscription.activeSourceAt(LocalDateTime.now(clock))))
                .ifPresent(subscription -> {
                    throw new BusinessException(SubscriptionErrorCode.SUBSCRIBED_ELSEWHERE, "스토어로 구독 중",
                            Map.of("source", subscription.getSource()));
                });
        billingKeyRepository.findAllByUserIdAndStatus(userId, BillingKeyStatus.ACTIVE)
                .forEach(BillingKey::delete);

        billingKeyRepository.save(
                BillingKey.issue(userId, provider, issued.billingKeyValue(), issued.maskedCard())
        );

        PaymentOrder order = PaymentOrder.createInitial(userId, planTier, amount, idempotencyKey);
        // 처음부터 「청구 중」 — 이 커밋 뒤 PG 를 부른다. 스토어 등록이 지우는 것은 청구 전(CREATED) 갱신 주문뿐이라 이 주문은 남는다
        order.markCharging();
        paymentOrderRepository.save(order);

        return new CreatedOrder(order.getId(), order.getPublicId(), issued.billingKeyValue());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChargeApplyResult applyInitialChargeResult(Long userId, Long orderId, PaymentResult chargeResult, LocalDateTime now) {
        // 구독 행 → 주문 행. 청구하는 사이 스토어로 구독했으면 결제는 기록하되 스토어 구독을 웹으로 덮지 않는다
        Optional<UserSubscription> locked = userSubscriptionRepository.lockByUserId(userId);
        PaymentOrder order = paymentOrderRepository.lockById(orderId).orElseThrow();
        Payment payment = Payment.request(order, order.getAmount());

        if (!chargeResult.success()) {
            payment.fail(chargeResult.failureCode(), chargeResult.failureMessage());
            order.markFailed();
            paymentRepository.save(payment);
            return ChargeApplyResult.failed();
        }

        payment.approve(chargeResult.pgTransactionId(), approvedAtOrNow(chargeResult, now));
        order.markPaid();
        paymentRepository.save(payment);

        if (locked.filter(subscription -> isStore(subscription.activeSourceAt(now))).isPresent()) {
            log.error("[payment] orderId={} 첫 웹 결제는 성공했지만 그사이 스토어로 구독했다 — 구독은 스토어 그대로, 환불 확인 필요", orderId);
            return ChargeApplyResult.superseded(locked.get());
        }
        UserSubscription subscription = locked
                .orElseGet(() ->
                        userSubscriptionRepository.save(UserSubscription.createBasic(userId)));

        subscription.activatePaid(order.getTargetTier(), now, now.plusMonths(1));

        return ChargeApplyResult.ok(subscription);
    }

    private static boolean isStore(SubscriptionSource source) {
        return source == SubscriptionSource.APP_STORE || source == SubscriptionSource.PLAY_STORE;
    }

    private LocalDateTime approvedAtOrNow(PaymentResult result, LocalDateTime now) {
        return result.approvedAt() != null ? result.approvedAt() : now;
    }
}
