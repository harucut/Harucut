package com.harucut.subscription.store.repository;

import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.entity.StorePurchase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StorePurchaseRepository extends JpaRepository<StorePurchase, Long> {

    // 잠금 읽기 — 같은 구매를 두 요청·알림이 동시에 바꾸지 않게(사용자 구독 행 잠금 뒤에 잡는다)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StorePurchase p where p.platform = :platform and p.storeKeyHash = :storeKeyHash")
    Optional<StorePurchase> lockByKey(@Param("platform") StorePlatform platform, @Param("storeKeyHash") String storeKeyHash);

    Optional<StorePurchase> findByPlatformAndStoreKeyHash(StorePlatform platform, String storeKeyHash);

    // 처음 묶는 구매면 1, 이미 있으면 0 — 유니크 위반을 예외로 받지 않는다(받으면 트랜잭션이 롤백 전용이 된다). 있는 행은 호출자가
    // 잠금 읽기(현재 읽기)로 다시 본다: RR 스냅샷은 먼저 끝난 요청이 넣은 행을 못 볼 수 있다
    @Modifying(flushAutomatically = true)
    @Query(value = "insert ignore into store_purchase (created_at, updated_at, user_id, platform, store_key, store_key_hash, product_id, "
            + "plan_tier, status, auto_renew, expires_at, environment, last_verified_at, last_event_at) values (:now, :now, :userId, :platform, "
            + ":storeKey, :storeKeyHash, :productId, :planTier, :status, :autoRenew, :expiresAt, :environment, :now, :signedAt)", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("platform") String platform, @Param("storeKey") String storeKey,
                       @Param("storeKeyHash") String storeKeyHash, @Param("productId") String productId, @Param("planTier") String planTier,
                       @Param("status") String status, @Param("autoRenew") boolean autoRenew, @Param("expiresAt") LocalDateTime expiresAt,
                       @Param("environment") String environment, @Param("signedAt") LocalDateTime signedAt,
                       @Param("now") LocalDateTime now);

    List<StorePurchase> findAllByUserIdAndPlatform(Long userId, StorePlatform platform);
}
