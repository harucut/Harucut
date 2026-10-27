package com.harucut.payment.batch;

import com.harucut.payment.batch.RenewalChargeTransactionService.ChargeTarget;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.Payment;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.PaymentStatus;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.payment.repository.PaymentRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalChargeTransactionService")
class RenewalChargeTransactionServiceTest {

    private static final Long ORDER_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2031, 1, 10, 0, 0);

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BillingKeyRepository billingKeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    private RenewalChargeTransactionService service;

    @BeforeEach
    void setUp() {
        service = new RenewalChargeTransactionService(paymentOrderRepository, paymentRepository,
                billingKeyRepository, userRepository, userSubscriptionRepository);
    }

    @Nested
    @DisplayName("markCharging — 긁는 중 도장")
    class MarkCharging {

        @Test
        @DisplayName("CREATED 주문이면 IN_PROGRESS로 바꾸고 청구 재료를 돌려준다")
        void stampsAndReturnsTarget() {
            PaymentOrder order = renewalOrder();
            User user = UserFixtures.localUser("renewal@harucut.com", "encoded");
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                    .willReturn(List.of(BillingKey.issue(USER_ID, PgProvider.MOCK, "bk-1", "1234-****")));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            ChargeTarget target = service.markCharging(ORDER_ID);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
            assertThat(target.orderPublicId()).isEqualTo(order.getPublicId());
            assertThat(target.billingKeyValue()).isEqualTo("bk-1");
            assertThat(target.customerKey()).isEqualTo(user.getPublicId());
            assertThat(target.planTier()).isEqualTo(PlanTier.PLUS);
            assertThat(target.amount()).isEqualTo(3900);
        }

        @Test
        @DisplayName("없는 주문이면 null이다")
        void missingOrderReturnsNull() {
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

            assertThat(service.markCharging(ORDER_ID)).isNull();

            then(billingKeyRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("CREATED가 아니면 null이다 — 이미 처리된 주문을 다시 긁지 않는다")
        void nonCreatedOrderReturnsNull() {
            PaymentOrder order = renewalOrder();
            order.markPaid();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));

            assertThat(service.markCharging(ORDER_ID)).isNull();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            then(billingKeyRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("준비와 청구 사이에 빌링키가 사라졌으면 시도 없이 실패 확정한다")
        void vanishedBillingKeyFailsWithoutAttempt() {
            PaymentOrder order = renewalOrder();
            UserSubscription subscription = activeSubscription();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                    .willReturn(List.of());
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(UserFixtures.localUser("renewal@harucut.com", "encoded")));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(subscription));

            assertThat(service.markCharging(ORDER_ID)).isNull();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        }

        @Test
        @DisplayName("사용자가 사라졌어도 시도 없이 실패 확정한다")
        void vanishedUserFailsWithoutAttempt() {
            PaymentOrder order = renewalOrder();
            UserSubscription subscription = activeSubscription();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                    .willReturn(List.of(BillingKey.issue(USER_ID, PgProvider.MOCK, "bk-1", "1234-****")));
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(subscription));

            assertThat(service.markCharging(ORDER_ID)).isNull();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        }
    }

    @Nested
    @DisplayName("#43 — 준비 뒤 스토어 구독으로 바뀐 경우")
    class SwitchedToStore {

        @Test
        @DisplayName("청구 직전 구독 행 → 주문 행을 잠그고 출처를 다시 본다 — 스토어면 청구하지 않고 주문을 지운다")
        void markChargingDropsOrder() {
            PaymentOrder order = renewalOrder();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(storeSubscription()));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));

            assertThat(service.markCharging(ORDER_ID)).isNull();

            then(paymentOrderRepository).should().delete(order);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(userSubscriptionRepository, paymentOrderRepository);
            inOrder.verify(userSubscriptionRepository).lockByUserId(USER_ID);
            inOrder.verify(paymentOrderRepository).lockById(ORDER_ID);
        }

        @Test
        @DisplayName("청구하는 사이 스토어로 바뀌었으면 성공해도 스토어 기간을 덮지 않는다(주문 PAID·결제 기록은 남김), 실패해도 스토어 구독을 PAST_DUE 로 만들지 않는다")
        void applyResultKeepsStorePeriod() {
            PaymentOrder order = renewalOrder();
            UserSubscription store = storeSubscription();
            LocalDateTime storeEnd = store.getCurrentPeriodEnd();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(store));

            service.applyResult(ORDER_ID, PaymentResult.success("tx-store", BASE_TIME), BASE_TIME);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(store.getCurrentPeriodEnd()).isEqualTo(storeEnd);
            assertThat(store.getSource()).isEqualTo(SubscriptionSource.APP_STORE);

            PaymentOrder failing = renewalOrder();
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(failing));
            service.applyResult(ORDER_ID, PaymentResult.failure("DECLINED", "거절"), BASE_TIME);
            assertThat(store.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("applyResult — 결과 반영")
    class ApplyResult {

        @Test
        @DisplayName("성공이면 결제 APPROVED·주문 PAID·구독 갱신이다 — PAST_DUE도 살아난다")
        void successRenewsSubscription() {
            PaymentOrder order = renewalOrder();
            UserSubscription subscription = activeSubscription();
            subscription.markPastDue();   // 어제 실패했던 구독이 오늘 재시도로 성공하는 경우
            LocalDateTime approvedAt = BASE_TIME.plusHours(2);
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(subscription));

            service.applyResult(ORDER_ID, PaymentResult.success("tx-9", approvedAt), BASE_TIME);

            Payment payment = savedPayment();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(payment.getPgTransactionId()).isEqualTo("tx-9");
            assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
            assertThat(payment.getAmount()).isEqualTo(3900);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(subscription.getCurrentPeriodStart()).isEqualTo(BASE_TIME);
            assertThat(subscription.getCurrentPeriodEnd()).isEqualTo(BASE_TIME.plusMonths(1));
        }

        @Test
        @DisplayName("PG가 승인 시각을 안 주면 배치 기준 시각으로 채운다")
        void missingApprovedAtFallsBackToBaseTime() {
            PaymentOrder order = renewalOrder();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID))
                    .willReturn(Optional.of(activeSubscription()));

            service.applyResult(ORDER_ID, PaymentResult.success("tx-9", null), BASE_TIME);

            assertThat(savedPayment().getApprovedAt()).isEqualTo(BASE_TIME);
        }

        @Test
        @DisplayName("실패면 결제 FAILED 행이 남고 주문 FAILED·구독 PAST_DUE다")
        void failureLeavesTrailAndMarksPastDue() {
            PaymentOrder order = renewalOrder();
            UserSubscription subscription = activeSubscription();
            LocalDateTime originalPeriodEnd = subscription.getCurrentPeriodEnd();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(subscription));

            service.applyResult(ORDER_ID, PaymentResult.failure("CARD_DECLINED", "한도 초과"), BASE_TIME);

            Payment payment = savedPayment();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");
            assertThat(payment.getFailureMessage()).isEqualTo("한도 초과");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
            // 실패는 주기를 건드리지 않는다 — 만료 배치가 유예를 계산할 기준이 남아야 한다
            assertThat(subscription.getCurrentPeriodEnd()).isEqualTo(originalPeriodEnd);
        }

        @Test
        @DisplayName("성공인데 거래번호가 없으면 믿지 않는다 — 실패로 기록한다")
        void successWithoutTransactionIdIsTreatedAsFailure() {
            PaymentOrder order = renewalOrder();
            UserSubscription subscription = activeSubscription();
            given(paymentOrderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentOrderRepository.lockById(ORDER_ID)).willReturn(Optional.of(order));
            given(userSubscriptionRepository.lockByUserId(USER_ID)).willReturn(Optional.of(subscription));

            service.applyResult(ORDER_ID, new PaymentResult(true, null, BASE_TIME, null, null), BASE_TIME);

            assertThat(savedPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        }
    }

    // ── fixtures ──────────────────────────────

    private PaymentOrder renewalOrder() {
        return PaymentOrder.createRenewal(USER_ID, PlanTier.PLUS, 3900, "renewal:5:20310110");
    }

    private UserSubscription activeSubscription() {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        subscription.activatePaid(PlanTier.PLUS, BASE_TIME.minusMonths(1), BASE_TIME.minusDays(1));
        return subscription;
    }

    private UserSubscription storeSubscription() {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        subscription.activateStore(SubscriptionSource.APP_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE, BASE_TIME,
                BASE_TIME.plusMonths(1), true);
        return subscription;
    }

    private Payment savedPayment() {
        ArgumentCaptor<Payment> captor = ArgumentCaptor.captor();
        then(paymentRepository).should().save(captor.capture());
        return captor.getValue();
    }
}
