package com.harucut.media.repository;

import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ComposeJobRepository extends JpaRepository<ComposeJob, Long> {

    // 폴링 조회 — 소유자 조건이 쿼리에 있어 남의 것과 없는 것이 똑같이 empty다 (미디어와 같은 404 규칙)
    Optional<ComposeJob> findByIdAndUser(Long id, User user);

    // 멱등 조회 — 같은 주체·같은 key 의 재시도에게 기존 Job 을 돌려준다
    Optional<ComposeJob> findByIdempotencyScopeAndIdempotencyKey(String idempotencyScope, String idempotencyKey);

    List<ComposeJob> findAllByParticipantIdAndEventIdOrderByIdDesc(Long participantId, Long eventId);

    /**
     * 행사 사진함 — 최신순 (created_at, id) 커서. 삭제 요청된 사진은 없다. 행 하나에 [ComposeJob, 참가 방식] 이 실린다.
     * 조건은 null 이면 걸지 않는다.
     */
    @Query("""
            select j, p.channel from ComposeJob j, Participant p
            where p.id = j.participantId and j.eventId = :eventId and j.deletedAt is null and j.purgedAt is null
              and (:status is null or j.status = :status)
              and (:channel is null or p.channel = :channel)
              and (:frameVersionId is null or j.frameVersionId = :frameVersionId)
              and (:from is null or j.createdAt >= :from)
              and (:to is null or j.createdAt < :to)
              and (:cursorAt is null or j.createdAt < :cursorAt or (j.createdAt = :cursorAt and j.id < :cursorId))
            order by j.createdAt desc, j.id desc
            """)
    List<Object[]> findEventPhotos(@Param("eventId") Long eventId,
                                   @Param("status") ComposeStatus status,
                                   @Param("channel") ParticipantChannel channel,
                                   @Param("frameVersionId") Long frameVersionId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("cursorAt") LocalDateTime cursorAt,
                                   @Param("cursorId") Long cursorId,
                                   Pageable pageable);

    // 보관 종료 대상 — 그 행사에서 기한(cutoff) 전에 만든, 아직 보이는 결과
    @Query("select j from ComposeJob j where j.eventId = :eventId and j.createdAt < :cutoff "
            + "and j.purgedAt is null and j.deletedAt is null and j.resultKey is not null")
    List<ComposeJob> findPurgeable(@Param("eventId") Long eventId, @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    // 행사에 아직 보일 결과·진행 중 작업이 남았는가 — 없으면 보관 종료할 수 있다.
    // 잠금 읽기(FOR UPDATE)라 스냅샷이 아니라 최신 커밋을 보고, 아직 커밋되지 않은 접수가 있으면 끝날 때까지 기다린다
    @Query(value = "select compose_job_id from compose_job where event_id = :eventId and purged_at is null and deleted_at is null "
            + "and status in ('PENDING', 'DONE') limit 1 for update", nativeQuery = true)
    List<Long> lockVisibleJobIds(@Param("eventId") Long eventId);

    // 보관 종료 표시 — purged_at 만 원자적으로(삭제 표시와 같은 이유: 엔티티 전체 UPDATE 가 다른 변경을 덮지 않게)
    @Modifying(flushAutomatically = true)
    @Query("update ComposeJob j set j.purgedAt = :now where j.id = :id and j.purgedAt is null")
    int markPurged(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 사진함 삭제 요청 — deleted_at 만 바꾼다. 엔티티 저장(전체 컬럼 UPDATE)은 같은 시각에 커밋된 워커의 DONE·resultKey 를
    // 낡은 값으로 덮을 수 있어 쓰지 않는다
    @Modifying(flushAutomatically = true)
    @Query("update ComposeJob j set j.deletedAt = :now where j.id = :id and j.deletedAt is null")
    int markDeleted(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 자기 UPDATE 뒤의 잠금 읽기(FOR UPDATE) — 영속성 컨텍스트를 거치지 않고 최신 커밋 값을 본다.
    // 삭제 요청과 완료 통지가 엇갈려도 나중에 커밋하는 쪽이 상대의 변경을 보고 결과 객체를 정리한다
    @Query(value = "select result_key from compose_job where compose_job_id = :id for update", nativeQuery = true)
    List<String> lockResultKey(@Param("id") Long id);

    @Query(value = "select deleted_at from compose_job where compose_job_id = :id for update", nativeQuery = true)
    List<Object> lockDeletedAt(@Param("id") Long id);

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
