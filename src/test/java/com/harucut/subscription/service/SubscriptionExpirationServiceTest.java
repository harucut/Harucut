package com.harucut.subscription.service;

import com.harucut.coupon.service.GrantActivationService;
import com.harucut.payment.config.PaymentProperties;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.store.config.StoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpirationService")
class SubscriptionExpirationServiceTest {

    private static final Long SUB_ID = 5L;
    private static final Long USER_ID = 1L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2032, 1, 10, 0, 0);

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private BillingKeyRepository billingKeyRepository;

    @Mock
    private GrantActivationService grantActivationService;

    private SubscriptionExpirationService service;

    @BeforeEach
    void setUp() {
        PaymentProperties payment = mock(PaymentProperties.class);
        lenient().when(payment.graceDays()).thenReturn(3);
        StoreProperties store = mock(StoreProperties.class);
        lenient().when(store.graceDays()).thenReturn(3);
        service = new SubscriptionExpirationService(userSubscriptionRepository, billingKeyRepository,
                grantActivationService, payment, store);
    }

    @Test
    @DisplayName("만료 대상이면 BASIC으로 강등하고 활성 빌링키를 해제한다")
    void expiresToFreeAndReleasesBillingKeys() {
        UserSubscription subscription = canceledSubscription();
        BillingKey billingKey = BillingKey.issue(USER_ID, PgProvider.MOCK, "bk-1", "1234-****");
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.of(subscription));
        given(billingKeyRepository.findAllByUserIdAndStatus(USER_ID, BillingKeyStatus.ACTIVE))
                .willReturn(List.of(billingKey));

        service.expire(SUB_ID, BASE_TIME);

        assertThat(subscription.getPlanTier()).isEqualTo(PlanTier.BASIC);
        assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(subscription.getCurrentPeriodStart()).isNull();
        assertThat(subscription.getCurrentPeriodEnd()).isNull();
        assertThat(subscription.isAutoRenew()).isFalse();
        assertThat(billingKey.getStatus()).isEqualTo(BillingKeyStatus.DELETED);
        then(grantActivationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예약 쿠폰이 있으면 강등 대신 grant를 개시한다 — 빌링키는 건드리지 않는다")
    void reservedCouponConvertsToGrantAndKeepsBillingKey() {
        UserSubscription subscription = canceledSubscription();
        subscription.reserveGrant(77L);
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.of(subscription));

        service.expire(SUB_ID, BASE_TIME);

        then(grantActivationService).should().activate(subscription, BASE_TIME);
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("구독이 사라졌으면 아무것도 하지 않는다")
    void missingSubscriptionDoesNothing() {
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.empty());

        service.expire(SUB_ID, BASE_TIME);

        then(grantActivationService).shouldHaveNoInteractions();
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("#43: 리더가 고른 뒤 갱신이 커밋됐으면(기한 연장·상태 ACTIVE) 잠그고 다시 봐서 건너뛴다 — 강등도 쿠폰 활성화도 없다")
    void skipsWhenRenewedMeanwhile() {
        UserSubscription renewed = UserSubscription.createBasic(USER_ID);
        renewed.activateStore(SubscriptionSource.APP_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                BASE_TIME.minusMonths(1), BASE_TIME.plusMonths(1), true);
        renewed.reserveGrant(77L);
        given(userSubscriptionRepository.lockById(SUB_ID)).willReturn(Optional.of(renewed));

        service.expire(SUB_ID, BASE_TIME);

        assertThat(renewed.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewed.getSource()).isEqualTo(SubscriptionSource.APP_STORE);
        then(grantActivationService).shouldHaveNoInteractions();
        then(billingKeyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("재확인 조건은 리더와 같다 — 스토어 ACTIVE 는 유예(3일) 뒤에만, 웹 PAST_DUE 도 유예 뒤에만")
    void expirableMatchesReader() {
        UserSubscription store = UserSubscription.createBasic(USER_ID);
        store.activateStore(SubscriptionSource.PLAY_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                BASE_TIME.minusMonths(1), BASE_TIME.minusDays(2), true);
        assertThat(service.isExpirable(store, BASE_TIME)).isFalse();
        store.activateStore(SubscriptionSource.PLAY_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                BASE_TIME.minusMonths(1), BASE_TIME.minusDays(3), true);
        assertThat(service.isExpirable(store, BASE_TIME)).isTrue();

        UserSubscription web = UserSubscription.createBasic(USER_ID);
        web.activatePaid(PlanTier.PLUS, BASE_TIME.minusMonths(1), BASE_TIME.minusDays(1));
        assertThat(service.isExpirable(web, BASE_TIME)).isFalse();   // 웹 ACTIVE 는 갱신 배치 몫
        web.markPastDue();
        assertThat(service.isExpirable(web, BASE_TIME)).isFalse();
        assertThat(service.isExpirable(canceledSubscription(), BASE_TIME)).isTrue();
    }

    // ── fixtures ──────────────────────────────

    private UserSubscription canceledSubscription() {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        subscription.activatePaid(PlanTier.PLUS, BASE_TIME.minusMonths(1), BASE_TIME.minusDays(1));
        subscription.cancelAutoRenew();
        return subscription;
    }
}
