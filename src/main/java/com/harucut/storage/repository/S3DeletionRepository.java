package com.harucut.storage.repository;

import com.harucut.storage.entity.S3Deletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface S3DeletionRepository extends JpaRepository<S3Deletion, Long> {

    // 삭제 예약 — 같은 key 가 이미 있으면 아무것도 하지 않는다(멱등, 0 을 돌려준다). 호출자 트랜잭션에 함께 커밋·롤백된다
    @Transactional
    @Modifying
    @Query(value = "insert ignore into s3_deletion (s3_key, key_hash, attempts, next_attempt_at, created_at, updated_at) "
            + "values (:s3Key, :keyHash, 0, :nextAttemptAt, :now, :now)", nativeQuery = true)
    int schedule(@Param("s3Key") String s3Key, @Param("keyHash") String keyHash,
                 @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("now") LocalDateTime now);

    // 업로드 책임으로 미뤄 둔 예약을 지금 지울 차례로 당긴다(횟수는 그대로). 행이 없으면 0
    @Transactional
    @Modifying
    @Query("update S3Deletion d set d.nextAttemptAt = :now, d.updatedAt = :now where d.keyHash = :keyHash")
    int makeDue(@Param("keyHash") String keyHash, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("delete from S3Deletion d where d.keyHash = :keyHash")
    int deleteByKeyHash(@Param("keyHash") String keyHash);

    @Transactional
    @Modifying
    // 실패 기록 — 읽은 횟수(previous)일 때만 +1. 그사이 다른 시도가 기록했으면 0 행(덮어쓰지 않는다)
    @Query("update S3Deletion d set d.attempts = d.attempts + 1, d.nextAttemptAt = :nextAttemptAt, d.lastError = :lastError, "
            + "d.updatedAt = :now where d.keyHash = :keyHash and d.attempts = :previous")
    int recordFailure(@Param("keyHash") String keyHash, @Param("previous") int previous,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("lastError") String lastError,
                      @Param("now") LocalDateTime now);

    // 재시도 차례 — 상한 아래이고 다음 시각이 지난 것
    List<S3Deletion> findTop100ByAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByIdAsc(int maxAttempts, LocalDateTime now);

    long countByAttemptsLessThan(int maxAttempts);

    long countByAttemptsGreaterThanEqual(int maxAttempts);
}
