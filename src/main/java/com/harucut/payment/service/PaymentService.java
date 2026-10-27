package com.harucut.payment.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.payment.dto.SubscribeRequest;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.payment.gateway.PaymentGateway;
import com.harucut.payment.gateway.dto.BillingChargeCommand;
import com.harucut.payment.gateway.dto.BillingKeyResult;
import com.harucut.payment.gateway.dto.IssueBillingKeyCommand;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.subscription.config.PlanPricingProperties;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final PaymentTransactionService paymentTransactionService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PlanPricingProperties planPricingProperties;
    private final Clock clock;

    public SubscriptionResponse subscribe(String publicId, SubscribeRequest request) {
        if (request.planTier() == PlanTier.BASIC) {
            throw new BusinessException(PaymentErrorCode.INVALID_TARGET_PLAN);
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND, "User not found."));

        Optional<PaymentOrder> existingOrder = paymentOrderRepository.findByIdempotencyKey(request.idempotencyKey());

        if (existingOrder.isPresent()) {
            return replay(existingOrder.get(), user.getId(), publicId, request);
        }

        requireNotSubscribed(user.getId());

        // customerKey는 요청에서 받지 않는다 — 로그인한 사용자의 publicId가 곧 customerKey다.
        // 프론트 값을 믿으면 남의 customerKey로 카드를 등록할 여지가 생긴다.
        BillingKeyResult issued = paymentGateway.issueBillingKey(
                new IssueBillingKeyCommand(publicId, request.authKey())
        );

        if (!issued.success()) {
            log.warn("[payment] 빌링키 발급 실패. userId={}, failureCode={}", user.getId(), issued.failureCode());
            throw new BusinessException(PaymentErrorCode.BILLING_KEY_ISSUE_FAILED);
        }

        int amount = planPricingProperties.priceOf(request.planTier());

        PaymentTransactionService.CreatedOrder created;
        try {
            created = paymentTransactionService.createInitialOrder(
                    user.getId(), request.planTier(), amount, request.idempotencyKey(), paymentGateway.provider(), issued
            );
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 주문을 커밋했다. 이쪽은 "처리 중"으로 응답한다.
            log.warn("[payment] 멱등 키 경쟁. userId={}", user.getId());
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        PaymentResult charged = paymentGateway.charge(new BillingChargeCommand(
                created.billingKeyValue(), created.orderPublicId(), amount, request.planTier().name() + "구독", publicId
        ));

        PaymentTransactionService.ChargeApplyResult applied = paymentTransactionService.applyInitialChargeResult(user.getId(), created.orderId(), charged, LocalDateTime.now(clock));

        if (!applied.success()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_FAILED);
        }

        return toResponse(applied.subscription(), publicId);
    }

    private void requireNotSubscribed(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        userSubscriptionRepository.findByUserId(userId)
                .filter(subscription -> subscription.effectiveTier(now) != PlanTier.BASIC)
                .ifPresent(subscription -> {
                    // 스토어로 구독 중이면 어디서 관리하는지 알린다(SUBS-009), 웹·쿠폰은 지금처럼 PAY-003
                    SubscriptionSource source = subscription.getSource();
                    if (source == SubscriptionSource.APP_STORE || source == SubscriptionSource.PLAY_STORE) {
                        throw new BusinessException(SubscriptionErrorCode.SUBSCRIBED_ELSEWHERE, "스토어로 구독 중입니다.",
                                Map.of("source", source));
                    }
                    throw new BusinessException(PaymentErrorCode.ALREADY_SUBSCRIBED);
                });
    }

    private SubscriptionResponse replay(PaymentOrder order, Long userId, String publicId, SubscribeRequest request) {
        if (!order.getUserId().equals(userId) || order.getTargetTier() != request.planTier()) {
            log.warn("[payment] 멱등 키 재사용 오류. userId={}, orderId={}", userId, order.getId());
            throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        }

        return switch (order.getStatus()) {
            case PAID -> userSubscriptionRepository.findByUserId(userId)
                    .map(subscription -> toResponse(subscription, publicId))
                    .orElseThrow(() -> new BusinessException(SubscriptionErrorCode.NO_ACTIVE_SUBSCRIPTION));
            case FAILED -> throw new BusinessException(PaymentErrorCode.PAYMENT_FAILED);
            // IN_PROGRESS는 갱신 배치 전용 도장이라 INITIAL 주문에선 안 나오지만,
            // switch는 enum 전체를 다뤄야 한다 — 의미는 CREATED와 같은 "결과 미확정"
            case CREATED, IN_PROGRESS -> throw new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT);
        };
    }

    private SubscriptionResponse toResponse(UserSubscription subscription, String publicId) {
        return SubscriptionResponse.of(subscription, subscription.effectiveTier(LocalDateTime.now(clock)), publicId);
    }
}
