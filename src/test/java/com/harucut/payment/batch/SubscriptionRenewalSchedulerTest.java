package com.harucut.payment.batch;

import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionRenewalScheduler")
class SubscriptionRenewalSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 2, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime BASE = LocalDate.of(2026, 8, 18).atStartOfDay();

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private RenewalPreparationService renewalPreparationService;

    @Mock
    private RenewalChargeService renewalChargeService;

    private SubscriptionRenewalScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        scheduler = new SubscriptionRenewalScheduler(userSubscriptionRepository, paymentOrderRepository,
                renewalPreparationService, renewalChargeService, clock);
    }

    @Test
    @DisplayName("오늘 자정을 기준으로 준비를 다 돌린 뒤 CREATED 주문을 청구한다")
    void preparesThenCharges() {
        given(userSubscriptionRepository.findRenewableIds(BASE)).willReturn(List.of(1L, 2L));
        given(paymentOrderRepository.findIdsByOrderTypeAndStatus(OrderType.RENEWAL, OrderStatus.CREATED)).willReturn(List.of(10L));

        scheduler.run();

        InOrder inOrder = Mockito.inOrder(renewalPreparationService, renewalChargeService);
        inOrder.verify(renewalPreparationService).prepare(1L, BASE);
        inOrder.verify(renewalPreparationService).prepare(2L, BASE);
        inOrder.verify(renewalChargeService).charge(10L, BASE);
    }

    @Test
    @DisplayName("준비·청구 한 건이 터져도 나머지는 계속된다")
    void oneFailureDoesNotStopTheRest() {
        given(userSubscriptionRepository.findRenewableIds(any())).willReturn(List.of(1L, 2L));
        given(paymentOrderRepository.findIdsByOrderTypeAndStatus(OrderType.RENEWAL, OrderStatus.CREATED)).willReturn(List.of(10L, 11L));
        willThrow(new IllegalStateException("준비 고장")).given(renewalPreparationService).prepare(1L, BASE);
        willThrow(new IllegalStateException("PG 응답 없음")).given(renewalChargeService).charge(10L, BASE);

        scheduler.run(LocalDate.of(2026, 8, 18));

        then(renewalPreparationService).should().prepare(2L, BASE);
        then(renewalChargeService).should().charge(11L, BASE);
    }
}
