package com.harucut.payment.batch;

import com.harucut.payment.config.PaymentProperties;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.service.SubscriptionExpirationService;
import com.harucut.subscription.store.config.StoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionExpirationScheduler")
class SubscriptionExpirationSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 2, 30);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime BASE = LocalDate.of(2026, 8, 18).atStartOfDay();

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private SubscriptionExpirationService subscriptionExpirationService;

    @Mock
    private PaymentProperties paymentProperties;

    @Mock
    private StoreProperties storeProperties;

    private SubscriptionExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        scheduler = new SubscriptionExpirationScheduler(userSubscriptionRepository, subscriptionExpirationService,
                paymentProperties, storeProperties, clock);
    }

    @Test
    @DisplayName("오늘 자정을 기준으로 웹·스토어 유예 한계를 각각 넘겨 대상을 고르고 하나씩 만료시킨다")
    void expiresTargetsOfToday() {
        given(paymentProperties.graceDays()).willReturn(3);
        given(storeProperties.graceDays()).willReturn(5);
        given(userSubscriptionRepository.findExpirableIds(BASE, BASE.minusDays(3), BASE.minusDays(5))).willReturn(List.of(5L, 6L));

        scheduler.run();

        then(subscriptionExpirationService).should().expire(5L, BASE);
        then(subscriptionExpirationService).should().expire(6L, BASE);
    }

    @Test
    @DisplayName("한 건이 터져도 나머지는 만료시키고, 처리한 수만 센다")
    void oneFailureDoesNotStopTheRest() {
        given(paymentProperties.graceDays()).willReturn(3);
        given(storeProperties.graceDays()).willReturn(3);
        given(userSubscriptionRepository.findExpirableIds(any(), any(), any())).willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("쿠폰 없음")).given(subscriptionExpirationService).expire(1L, BASE);

        assertThat(scheduler.run(LocalDate.of(2026, 8, 18))).isEqualTo(1);

        then(subscriptionExpirationService).should().expire(2L, BASE);
    }
}
