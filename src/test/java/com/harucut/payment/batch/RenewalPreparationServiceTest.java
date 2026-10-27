package com.harucut.payment.batch;

import com.harucut.coupon.service.GrantActivationService;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.subscription.config.PlanPricingProperties;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalPreparationService")
class RenewalPreparationServiceTest {

    private static final Long SUB_ID = 5L;
    private static final Long USER_ID = 1L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2031, 1, 10, 0, 0);
    private static final String TODAY_KEY = "renewal:5:20310110";
    private static final List<OrderStatus> UNRESOLVED = List.of(OrderStatus.CREATED, OrderStatus.IN_PROGRESS);

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private BillingKeyRepository billingKeyRepository;

    @Mock
    private GrantActivationService grantActivationService;

    private RenewalPreparationService service;

    @BeforeEach
    void setUp() {
        service = new RenewalPreparationService(userSubscriptionRepository, paymentOrderRepository,
                billingKeyRepository, new PlanPricingProperties(0, 3900, 9900), grantActivationService);
    }

    @Test
    @DisplayName("정상 대상이면 오늘 날짜 멱등키로 RENEWAL 주문을 만든다")
    void createsRenewalOrder() {
        givenSubscription(activePlus());
        given(paymentOrderRepository.existsByUserIdAndOrderTypeAndStatusIn(USER_ID, OrderType.RENEWAL, UNRESOLVED))
                .willReturn(false);
        given(paymentOrderRepository.existsByIdempotencyKey(TODAY_KEY)).willReturn(false);
        given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                .willReturn(List.of(billingKey()));

        service.prepare(SUB_ID, BASE_TIME);

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.captor();
        then(paymentOrderRepository).should().save(captor.capture());
        PaymentOrder order = captor.getValue();
        assertThat(order.getOrderType()).isEqualTo(OrderType.RENEWAL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        assertThat(order.getAmount()).isEqualTo(3900);
        assertThat(order.getIdempotencyKey()).isEqualTo(TODAY_KEY);
    }

    @Test
    @DisplayName("구독이 사라졌으면 아무것도 하지 않는다")
    void missingSubscriptionDoesNothing() {
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.empty());

        service.prepare(SUB_ID, BASE_TIME);

        then(grantActivationService).shouldHaveNoInteractions();
        then(paymentOrderRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예약 쿠폰이 있으면 청구 대신 grant를 개시한다 — 주문은 만들지 않는다")
    void reservedCouponActivatesGrantInsteadOfCharging() {
        UserSubscription subscription = activePlus();
        subscription.reserveGrant(77L);
        givenSubscription(subscription);

        service.prepare(SUB_ID, BASE_TIME);

        then(grantActivationService).should().activate(subscription, BASE_TIME);
        then(paymentOrderRepository).shouldHaveNoInteractions();
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("결과 미확정 주문이 남아 있으면 새 청구를 만들지 않는다 — 모르면 다시 긁지 않는다")
    void unresolvedOrderBlocksNewCharge() {
        givenSubscription(activePlus());
        given(paymentOrderRepository.existsByUserIdAndOrderTypeAndStatusIn(USER_ID, OrderType.RENEWAL, UNRESOLVED))
                .willReturn(true);

        service.prepare(SUB_ID, BASE_TIME);

        then(paymentOrderRepository).should(never()).save(any());
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("오늘 멱등키가 이미 있으면 건너뛴다 — 재시도는 내일이다")
    void todayKeyAlreadyExistsSkips() {
        givenSubscription(activePlus());
        given(paymentOrderRepository.existsByUserIdAndOrderTypeAndStatusIn(USER_ID, OrderType.RENEWAL, UNRESOLVED))
                .willReturn(false);
        given(paymentOrderRepository.existsByIdempotencyKey(TODAY_KEY)).willReturn(true);

        service.prepare(SUB_ID, BASE_TIME);

        then(paymentOrderRepository).should(never()).save(any());
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("활성 빌링키가 없으면 시도 없이 PAST_DUE로 내린다")
    void noBillingKeyMarksPastDue() {
        UserSubscription subscription = activePlus();
        givenSubscription(subscription);
        given(paymentOrderRepository.existsByUserIdAndOrderTypeAndStatusIn(USER_ID, OrderType.RENEWAL, UNRESOLVED))
                .willReturn(false);
        given(paymentOrderRepository.existsByIdempotencyKey(TODAY_KEY)).willReturn(false);
        given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                .willReturn(List.of());

        service.prepare(SUB_ID, BASE_TIME);

        assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        then(paymentOrderRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("#43: 리더가 고른 뒤 스토어 등록이 커밋됐으면(출처 APP_STORE) 잠그고 다시 봐서 건너뛴다 — 예약 쿠폰을 쓰지도, 주문을 만들지도 않는다")
    void skipsWhenSwitchedToStore() {
        UserSubscription store = UserSubscription.createBasic(USER_ID);
        store.activateStore(SubscriptionSource.APP_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                BASE_TIME.minusDays(1), BASE_TIME.plusMonths(1), true);
        store.reserveGrant(77L);
        ReflectionTestUtils.setField(store, "id", SUB_ID);
        givenSubscription(store);

        service.prepare(SUB_ID, BASE_TIME);

        then(grantActivationService).shouldHaveNoInteractions();
        then(paymentOrderRepository).should(never()).save(any());
        assertThat(store.getSource()).isEqualTo(SubscriptionSource.APP_STORE);
    }

    @Test
    @DisplayName("재확인 조건은 리더와 같다 — 웹(또는 출처 없음) ACTIVE·PAST_DUE 이고 기한이 기준 시각 이전")
    void renewableMatchesReader() {
        assertThat(RenewalPreparationService.isRenewable(activePlus(), BASE_TIME)).isTrue();
        UserSubscription future = UserSubscription.createBasic(USER_ID);
        future.activatePaid(PlanTier.PLUS, BASE_TIME, BASE_TIME.plusDays(1));
        assertThat(RenewalPreparationService.isRenewable(future, BASE_TIME)).isFalse();
        UserSubscription canceled = activePlus();
        canceled.cancelAutoRenew();
        assertThat(RenewalPreparationService.isRenewable(canceled, BASE_TIME)).isFalse();
    }

    // ── fixtures ──────────────────────────────

    private UserSubscription activePlus() {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        subscription.activatePaid(PlanTier.PLUS, BASE_TIME.minusMonths(1), BASE_TIME.minusDays(1));
        // 멱등키가 구독 id로 만들어진다 — 영속 전 엔티티라 직접 채운다
        ReflectionTestUtils.setField(subscription, "id", SUB_ID);
        return subscription;
    }

    private void givenSubscription(UserSubscription subscription) {
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.of(subscription));
    }

    private BillingKey billingKey() {
        return BillingKey.issue(USER_ID, PgProvider.MOCK, "bk-1", "1234-****");
    }
}
