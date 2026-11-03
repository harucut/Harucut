package com.harucut.subscription.repository;

import com.harucut.subscription.entity.UserSubscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    boolean existsByUserId(Long userId);

    // 갱신 배치의 대상 — 웹 정기결제만(스토어 구독은 스토어가 갱신하고 카드도 없다, ADR-0011 §2).
    // RenewalPreparationService.isRenewable 이 처리 직전에 같은 조건을 다시 본다 — 둘을 같이 바꿀 것
    @Query("""
            select s.id from UserSubscription s
            where s.subscriptionStatus in (com.harucut.subscription.enums.SubscriptionStatus.ACTIVE,
                                           com.harucut.subscription.enums.SubscriptionStatus.PAST_DUE)
              and (s.source is null or s.source = com.harucut.subscription.enums.SubscriptionSource.WEB)
              and s.currentPeriodEnd is not null
              and s.currentPeriodEnd <= :baseTime
            order by s.id
            """)
    List<Long> findRenewableIds(@Param("baseTime") LocalDateTime baseTime);

    // 만료 배치의 대상 — CANCELED·GRANTED 는 기한에, 웹 PAST_DUE 는 유예(payment.grace-days) 뒤에, 스토어 구독은 갱신 알림이 없는 채 유예(billing.store.grace-days)가 지나면.
    // SubscriptionExpirationService.isExpirable 이 처리 직전에 같은 조건을 다시 본다 — 둘을 같이 바꿀 것
    @Query("""
            select s.id from UserSubscription s
            where s.currentPeriodEnd is not null
              and ((s.subscriptionStatus in (com.harucut.subscription.enums.SubscriptionStatus.CANCELED,
                                             com.harucut.subscription.enums.SubscriptionStatus.GRANTED)
                    and s.currentPeriodEnd <= :baseTime)
                or (s.subscriptionStatus = com.harucut.subscription.enums.SubscriptionStatus.PAST_DUE
                    and (s.source is null or s.source = com.harucut.subscription.enums.SubscriptionSource.WEB)
                    and s.currentPeriodEnd <= :graceLimit)
                or (s.source in (com.harucut.subscription.enums.SubscriptionSource.APP_STORE,
                                 com.harucut.subscription.enums.SubscriptionSource.PLAY_STORE)
                    and s.subscriptionStatus in (com.harucut.subscription.enums.SubscriptionStatus.ACTIVE,
                                                 com.harucut.subscription.enums.SubscriptionStatus.PAST_DUE)
                    and s.currentPeriodEnd <= :storeGraceLimit))
            order by s.id
            """)
    List<Long> findExpirableIds(@Param("baseTime") LocalDateTime baseTime, @Param("graceLimit") LocalDateTime graceLimit,
                                @Param("storeGraceLimit") LocalDateTime storeGraceLimit);

    Optional<UserSubscription> findByUserId(Long userId);

    // 스토어 구매·알림이 구독을 바꿀 때 — 잠금 순서는 늘 구독 행 → 스토어 구매 행(ADR-0011 §2)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSubscription s where s.userId = :userId")
    Optional<UserSubscription> lockByUserId(@Param("userId") Long userId);

    // 만료 배치가 고른 행을 처리 직전에 다시 본다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSubscription s where s.id = :id")
    Optional<UserSubscription> lockById(@Param("id") Long id);

    // 같은 잠금을 public id 로 — 트랜잭션의 첫 문장으로 쓴다(RR 스냅샷이 잠금을 얻은 뒤에 잡히게)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSubscription s where s.userId = (select u.id from User u where u.publicId = :publicId)")
    Optional<UserSubscription> lockByUserPublicId(@Param("publicId") String publicId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserSubscription s WHERE s.userId = :userId")
    void deleteByUserId(Long userId);
}