package com.harucut.subscription.repository;

import com.harucut.subscription.entity.UserSubscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    boolean existsByUserId(Long userId);

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