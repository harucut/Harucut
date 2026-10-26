package com.harucut.media.repository;

import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ComposeJobRepository extends JpaRepository<ComposeJob, Long> {

    // 폴링 조회 — 소유자 조건이 쿼리에 있어 남의 것과 없는 것이 똑같이 empty다 (미디어와 같은 404 규칙)
    Optional<ComposeJob> findByIdAndUser(Long id, User user);

    // 멱등 조회 — 같은 주체·같은 key 의 재시도에게 기존 Job 을 돌려준다
    Optional<ComposeJob> findByIdempotencyScopeAndIdempotencyKey(String idempotencyScope, String idempotencyKey);

    List<ComposeJob> findAllByParticipantIdAndEventIdOrderByIdDesc(Long participantId, Long eventId);

    // 부스 화면이 지금 회차의 합성 상태를 본다 — 회차에 작업이 여럿이면 마지막 것
    Optional<ComposeJob> findFirstByCaptureRunIdOrderByIdDesc(Long captureRunId);

    // 적체 게이지용 — 인덱스(status, started_at)의 앞 컬럼만 타므로 PENDING 수에 비례한다.
    // PENDING 이 적을 때 싸고, 많을 때 비싸진다. 그 순간이 알아야 하는 순간이라 감수한다
    long countByStatus(ComposeStatus status);

    @Query("SELECT j.resultKey FROM ComposeJob j WHERE j.user.id = :userId AND j.resultKey IS NOT NULL")
    List<String> findResultKeysByUserId(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ComposeJob j WHERE j.user.id = :userId")
    void deleteByUserId(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ComposeJob j
            SET j.startedAt = :now
            WHERE j.id = :jobId
                AND j.status = ComposeStatus.PENDING
                AND (j.startedAt IS NULL OR j.startedAt < :staleBefore)
            """)
    int claim(Long jobId, LocalDateTime now, LocalDateTime staleBefore);

    // 완료·실패 전이는 "읽고 PENDING 확인 후 저장"이 아니라 조건부 UPDATE 다. 두 통지가 동시에 와도
    // 행 잠금 뒤 두 번째 UPDATE 는 0행이 되어, 결과·미디어·원본 삭제가 정확히 한 번만 일어난다.
    // 영향 행 수가 곧 "이 호출이 전이를 이겼는가"다.
    //
    // clearAutomatically 를 걸지 않는다 — 호출자가 같은 트랜잭션에서 이미 읽은 Job 의 user·sourceKeys 를
    // 이어서 쓴다. 영속성 컨텍스트의 Job 은 status 가 낡은 채 남지만, 그 엔티티를 고치지 않으므로
    // 커밋 때 덮어쓰지 않는다
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ComposeJob j
            SET j.status = :done, j.resultKey = :resultKey
            WHERE j.id = :jobId AND j.status = :pending
            """)
    int markDone(Long jobId, String resultKey, ComposeStatus pending, ComposeStatus done);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ComposeJob j SET j.mediaId = :mediaId WHERE j.id = :jobId")
    int attachMedia(Long jobId, Long mediaId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ComposeJob j
            SET j.status = :failed, j.failureReason = :reason
            WHERE j.id = :jobId AND j.status = :pending
            """)
    int markFailed(Long jobId, String reason, ComposeStatus pending, ComposeStatus failed);

    @Query("""
            SELECT j FROM ComposeJob j
            LEFT JOIN FETCH j.user
            WHERE j.status = ComposeStatus.PENDING
                AND (j.startedAt IS NULL OR j.startedAt < :staleBefore)
            ORDER BY j.id
            """)
    List<ComposeJob> findStalled(LocalDateTime staleBefore, Pageable pageable);
}
