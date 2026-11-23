package com.harucut.payment.batch;

import com.harucut.payment.config.PaymentProperties;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.service.SubscriptionExpirationService;
import com.harucut.subscription.store.config.StoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 구독 만료 — 기한이 지난 구독을 하나씩 무료로 내린다(예약 쿠폰이 있으면 grant 로). 건마다 SubscriptionExpirationService.expire 가
 * 제 트랜잭션이라 한 건이 터져도 그 건만 롤백되고 나머지는 계속된다. 중복 기동은 막지 않는다(UserDeletionScheduler 와 같다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpirationScheduler {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionExpirationService subscriptionExpirationService;
    private final PaymentProperties paymentProperties;
    private final StoreProperties storeProperties;
    private final Clock clock;

    // 갱신(2:00) 뒤 30분 — PAST_DUE 3일째의 마지막 재청구가 먼저 돌고, 실패한 것만 여기서 강등된다
    @Scheduled(cron = "0 30 2 * * *")
    public void run() {
        run(LocalDate.now(clock));
    }

    /** runDate 자정을 기준시각으로 만료 대상을 처리한다. @return 처리한 수(처리 직전 재확인에서 빠진 건 포함, 터진 건 제외) */
    public int run(LocalDate runDate) {
        LocalDateTime baseTime = runDate.atStartOfDay();
        // 웹 PAST_DUE 는 유예(payment.grace-days) 뒤에, 스토어 구독(ADR-0011 §2)은 갱신 알림 없이 유예(billing.store.grace-days)가 지나면
        List<Long> ids = userSubscriptionRepository.findExpirableIds(baseTime,
                baseTime.minusDays(paymentProperties.graceDays()), baseTime.minusDays(storeProperties.graceDays()));
        int expired = 0;
        for (Long subscriptionId : ids) {
            try {
                subscriptionExpirationService.expire(subscriptionId, baseTime);
                expired++;
            } catch (RuntimeException e) {
                log.warn("[만료 배치] subscriptionId={} 처리 실패 — 건너뛰고 계속", subscriptionId, e);
            }
        }
        log.info("[만료 배치] {} 기준 대상 {}건 중 {}건 처리", runDate, ids.size(), expired);
        return expired;
    }
}
