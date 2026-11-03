package com.harucut.payment.batch;

import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 구독 갱신 — 두 단계다. 준비(구독마다 제 트랜잭션에서 오늘의 주문을 만든다) 뒤 청구(CREATED 주문마다. PG 호출은 트랜잭션 밖이고
 * RenewalChargeTransactionService 가 건별로 두 번 커밋한다). 한 건이 터져도 나머지는 계속된다.
 * 청구가 터진 주문은 IN_PROGRESS 도장이 남아 다음 날 준비 단계가 "미확정 주문 있음"으로 건너뛴다 — 같은 주문이 PG 로 두 번 나가지 않는다.
 * 중복 기동은 막지 않는다 — 앱이 한 대라 하루 한 번 돈다(docs/decisions.md 2026-11-03). 두 대가 되면 잠금이 필요하다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionRenewalScheduler {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RenewalPreparationService renewalPreparationService;
    private final RenewalChargeService renewalChargeService;
    private final Clock clock;

    @Scheduled(cron = "0 0 2 * * *")
    public void run() {
        run(LocalDate.now(clock));
    }

    /** runDate 자정을 기준시각으로 준비 → 청구를 한 바퀴 돈다 */
    public void run(LocalDate runDate) {
        LocalDateTime baseTime = runDate.atStartOfDay();
        int prepared = 0;
        for (Long subscriptionId : userSubscriptionRepository.findRenewableIds(baseTime)) {
            try {
                renewalPreparationService.prepare(subscriptionId, baseTime);
                prepared++;
            } catch (RuntimeException e) {
                log.warn("[갱신 배치] subscriptionId={} 준비 실패 — 건너뛰고 계속", subscriptionId, e);
            }
        }
        int charged = 0;
        for (Long orderId : paymentOrderRepository.findIdsByOrderTypeAndStatus(OrderType.RENEWAL, OrderStatus.CREATED)) {
            try {
                renewalChargeService.charge(orderId, baseTime);
                charged++;
            } catch (RuntimeException e) {
                log.warn("[갱신 배치] orderId={} 청구 실패 — 건너뛰고 계속", orderId, e);
            }
        }
        log.info("[갱신 배치] {} 기준 준비 {}건, 청구 {}건", runDate, prepared, charged);
    }
}
