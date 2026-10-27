package com.harucut.push.batch;

import com.harucut.push.repository.PushDedupRepository;
import com.harucut.push.service.PushMessage;
import com.harucut.push.service.PushRequestedEvent;
import com.harucut.push.service.PushType;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 구독 만료 3일 전 푸시(SUBSCRIPTION_EXPIRING). 대상은 갱신되지 않는 구독 — 자동 갱신을 끈(CANCELED)·쿠폰(GRANTED) 구독이
 * {@code 오늘+3일} 하루 안에 끝날 때다. 이어 받을 쿠폰이 예약된 구독은 끝나지 않으니 뺀다.
 *
 * <p>만료 배치는 기한이 지난 다음 날 02:30 에 강등한다 — 기한이 D+3 이면 D+4 새벽이다.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionExpiringNoticeService {

    static final int DAYS_BEFORE = 3;
    private static final Set<SubscriptionStatus> NOT_RENEWING = Set.of(SubscriptionStatus.CANCELED, SubscriptionStatus.GRANTED);

    private final EntityManager entityManager;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PushDedupRepository pushDedupRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> candidates(LocalDate today) {
        return entityManager.createQuery("""
                        select s.id from UserSubscription s
                        where s.subscriptionStatus in :statuses and s.reservedUserCouponId is null
                            and s.currentPeriodEnd >= :from and s.currentPeriodEnd < :to
                        """, Long.class)
                .setParameter("statuses", NOT_RENEWING)
                .setParameter("from", windowStart(today))
                .setParameter("to", windowStart(today).plusDays(1))
                .getResultList();
    }

    // 잠금 순서: 구독 행 → push_dedup. 고른 뒤 재구독·쿠폰 예약·기간 변경이 커밋됐을 수 있어 잠그고 다시 본다.
    // 같은 기간(기한)에 한 번만 — 키에 기한을 넣어, 기간이 바뀌면 새 기간에서 다시 알린다
    @Transactional
    public boolean notify(Long subscriptionId, LocalDate today) {
        UserSubscription subscription = userSubscriptionRepository.lockById(subscriptionId).orElse(null);
        if (subscription == null || !isTarget(subscription, today)) {
            return false;
        }
        String key = PushType.SUBSCRIPTION_EXPIRING.name() + ":" + subscriptionId + ":" + subscription.getCurrentPeriodEnd();
        if (pushDedupRepository.recordOnce(key, LocalDateTime.now(clock)) != 1) {
            return false;
        }
        eventPublisher.publishEvent(new PushRequestedEvent(subscription.getUserId(),
                PushMessage.of(PushType.SUBSCRIPTION_EXPIRING)));
        return true;
    }

    // candidates 의 조건과 같다 — 둘을 같이 바꿀 것
    static boolean isTarget(UserSubscription subscription, LocalDate today) {
        LocalDateTime end = subscription.getCurrentPeriodEnd();
        LocalDateTime from = windowStart(today);
        return NOT_RENEWING.contains(subscription.getSubscriptionStatus())
                && subscription.getReservedUserCouponId() == null
                && end != null && !end.isBefore(from) && end.isBefore(from.plusDays(1));
    }

    private static LocalDateTime windowStart(LocalDate today) {
        return today.plusDays(DAYS_BEFORE).atStartOfDay();
    }
}
