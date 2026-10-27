package com.harucut.push.repository;

import com.harucut.push.entity.PushDedup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PushDedupRepository extends JpaRepository<PushDedup, Long> {

    // 처음이면 1, 이미 보냈으면 0 — 유니크 위반을 예외로 받지 않는다(받으면 트랜잭션이 롤백 전용이 된다)
    @Modifying
    @Query(value = "insert ignore into push_dedup (dedup_key, created_at) values (:dedupKey, :now)", nativeQuery = true)
    int recordOnce(@Param("dedupKey") String dedupKey, @Param("now") LocalDateTime now);
}
