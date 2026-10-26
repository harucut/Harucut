package com.harucut.export.repository;

import com.harucut.export.entity.ExportJob;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.enums.ExportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

    Optional<ExportJob> findByPublicId(String publicId);

    List<ExportJob> findByEventIdAndRequestedByUserIdOrderByIdDesc(Long eventId, Long requestedByUserId, Pageable pageable);

    List<ExportJob> findAllByStatus(ExportStatus status);

    // 진행 신호가 끊긴 RUNNING — 그 인스턴스가 죽었다(살아 있는 작업자는 heartbeat 로 updatedAt 을 밀어 둔다)
    List<ExportJob> findAllByStatusAndUpdatedAtBefore(ExportStatus status, LocalDateTime before);

    // 여러 행사 묶음의 내 목록 — 그 범위(공간·프로젝트)에서 내가 만든 것, 최신순
    List<ExportJob> findByWorkspaceIdAndScopeAndProjectIdAndRequestedByUserIdOrderByIdDesc(
            Long workspaceId, ExportScope scope, Long projectId, Long requestedByUserId, Pageable pageable);

    List<ExportJob> findAllByStatusAndExpiresAtBefore(ExportStatus status, LocalDateTime now);

    // 상태 전이는 전부 벌크 UPDATE 라 BaseEntity 감사(updatedAt)를 거치지 않는다 — updatedAt 을 직접 넣는다

    // 워커의 선점 — PENDING 인 것만 RUNNING 으로
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.RUNNING, e.updatedAt = :now "
            + "where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.PENDING")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 완료 — 그사이 무효(EXPIRED)가 됐으면 0 이고, 호출자가 올린 객체를 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.DONE, e.sizeBytes = :size, "
            + "e.expiresAt = :expiresAt, e.updatedAt = :now where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.RUNNING")
    int complete(@Param("id") Long id, @Param("size") long size, @Param("expiresAt") LocalDateTime expiresAt,
                 @Param("now") LocalDateTime now);

    // 진행 신호 — 압축 중인 작업자가 주기적으로. 다른 인스턴스의 정리가 살아 있는 작업을 실패로 만들지 않게
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.updatedAt = :now where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.RUNNING")
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 종료하는 작업자가 하던 것을 내려놓는다 — 다른 인스턴스(또는 다음 기동)가 처음부터 다시 만든다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.PENDING, e.updatedAt = :now "
            + "where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.RUNNING")
    int requeue(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.FAILED, e.failureReason = :reason, e.updatedAt = :now "
            + "where e.id = :id and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING)")
    int fail(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    // 진행 신호가 끊긴 RUNNING 만 실패로 — 읽은 뒤 그 작업자가 신호를 보냈으면 0(조건을 UPDATE 에 함께 건다)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.FAILED, e.failureReason = :reason, e.updatedAt = :now "
            + "where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.RUNNING and e.updatedAt < :before")
    int failStale(@Param("id") Long id, @Param("before") LocalDateTime before, @Param("reason") String reason,
                  @Param("now") LocalDateTime now);

    // 무효화 — 아직 살아 있는 것(PENDING·RUNNING·DONE)만 EXPIRED 로. 이긴 쪽이 객체를 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.EXPIRED, e.updatedAt = :now where e.id = :id "
            + "and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING, "
            + "com.harucut.export.enums.ExportStatus.DONE)")
    int expire(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 이 사진을 담은 살아 있는 묶음 — 사진 삭제 때 무효로 한다
    @Query("select distinct e from ExportJob e, ExportJobItem i where i.exportJobId = e.id and i.composeJobId = :composeJobId "
            + "and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING, "
            + "com.harucut.export.enums.ExportStatus.DONE)")
    List<ExportJob> findLiveContaining(@Param("composeJobId") Long composeJobId);
}
