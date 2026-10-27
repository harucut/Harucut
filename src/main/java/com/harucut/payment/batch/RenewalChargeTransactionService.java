package com.harucut.payment.batch;

import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.Payment;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.payment.repository.PaymentRepository;
import com.harucut.push.service.PushMessage;
import com.harucut.push.service.PushRequestedEvent;
import com.harucut.push.service.PushType;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RenewalChargeTransactionService {

    public record ChargeTarget(
            String orderPublicId,
            String billingKeyValue,
            String customerKey,
            PlanTier planTier,
            int amount) {}

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRepository paymentRepository;
    private final BillingKeyRepository billingKeyRepository;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final ApplicationEventPublisher eventPublisher;

    // "긁는 중" 도장을 찍고 커밋한다 — PG 호출은 반드시 이 커밋 이후여야 한다
    @Transactional
    public ChargeTarget markCharging(Long orderId) {
        PaymentOrder seen = paymentOrderRepository.findById(orderId).orElse(null);
        if (seen == null) {
            return null;
        }
        // 준비 커밋 뒤 스토어 구독으로 바뀌었을 수 있다 — 구독 행 → 주문 행 순서로 잠그고 출처를 다시 본다(ADR-0011 §2).
        // 스토어로 바뀌었으면 청구하지 않고 주문을 지운다(아직 청구 전이라 결제 이력이 없다)
        Optional<UserSubscription> subscription = userSubscriptionRepository.lockByUserId(seen.getUserId());
        PaymentOrder order = paymentOrderRepository.lockById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.CREATED) {
            return null;
        }
        if (subscription.map(UserSubscription::getSource).filter(RenewalChargeTransactionService::isStore).isPresent()) {
            paymentOrderRepository.delete(order);
            return null;
        }

        List<BillingKey> billingKeys = billingKeyRepository
                .findAllByUserIdAndStatus(order.getUserId(), BillingKeyStatus.ACTIVE);
        User user = userRepository.findById(order.getUserId()).orElse(null);
        if (billingKeys.isEmpty() || user == null) {
            // 준비 커밋과 청구 사이에 카드·사용자가 사라진 드문 경우 — 시도 없이 실패 확정
            order.markFailed();
            subscription.ifPresent(UserSubscription::markPastDue);
            notifyPaymentFailed(order.getUserId());
            return null;
        }

        order.markCharging();
        return new ChargeTarget(order.getPublicId(), billingKeys.get(0).getBillingKeyValue(),
                user.getPublicId(), order.getTargetTier(), order.getAmount());
    }

    // 성공이든 실패든 이 트랜잭션은 커밋된다 — 결제 이력은 어느 쪽이든 반드시 남는다
    @Transactional
    public void applyResult(Long orderId, PaymentResult result, LocalDateTime baseTime) {
        PaymentOrder seen = paymentOrderRepository.findById(orderId).orElseThrow();
        // 구독 행 → 주문 행 순서. 청구하는 사이 스토어 구독으로 바뀌었으면 스토어 기간을 덮지 않는다
        Optional<UserSubscription> subscription = userSubscriptionRepository.lockByUserId(seen.getUserId());
        PaymentOrder order = paymentOrderRepository.lockById(orderId).orElseThrow();
        Optional<UserSubscription> web = subscription.filter(s -> !isStore(s.getSource()));
        Payment payment = Payment.request(order, order.getAmount());

        if (result.success() && result.pgTransactionId() != null) {
            payment.approve(result.pgTransactionId(), result.approvedAt() != null ? result.approvedAt() : baseTime);
            order.markPaid();
            paymentRepository.save(payment);
            web.ifPresent(s -> s.renew(baseTime, baseTime.plusMonths(1)));
            if (web.isEmpty() && subscription.isPresent()) {
                log.error("[갱신 배치] orderId={} 웹 갱신 청구는 성공했지만 그사이 스토어 구독으로 바뀌었다 — 구독은 그대로, 환불 확인 필요", orderId);
            }
            return;
        }

        payment.fail(result.failureCode(), result.failureMessage());
        order.markFailed();
        paymentRepository.save(payment);
        web.ifPresent(UserSubscription::markPastDue);
        // 스토어로 바뀐 구독은 웹 결제 실패를 알리지 않는다(이용은 스토어 기간으로 이어진다)
        if (web.isPresent()) {
            notifyPaymentFailed(order.getUserId());
        }
    }

    // 커밋 뒤 발송 — 주문을 실패로 확정하는 트랜잭션에서만 부른다(롤백되면 나가지 않는다)
    private void notifyPaymentFailed(Long userId) {
        eventPublisher.publishEvent(new PushRequestedEvent(userId, PushMessage.of(PushType.PAYMENT_FAILED)));
    }

    private static boolean isStore(SubscriptionSource source) {
        return source == SubscriptionSource.APP_STORE || source == SubscriptionSource.PLAY_STORE;
    }
}
