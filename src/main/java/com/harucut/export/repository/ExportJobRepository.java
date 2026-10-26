package com.harucut.export.repository;

import com.harucut.export.entity.ExportJob;
import com.harucut.export.enums.ExportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

    Optional<ExportJob> findByPublicId(String publicId);

    List<ExportJob> findByEventIdAndRequestedByUserIdOrderByIdDesc(Long eventId, Long requestedByUserId, Pageable pageable);

    List<ExportJob> findAllByStatus(ExportStatus status);

    List<ExportJob> findAllByStatusAndExpiresAtBefore(ExportStatus status, LocalDateTime now);

    // 워커의 선점 — PENDING 인 것만 RUNNING 으로
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.RUNNING "
            + "where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.PENDING")
    int claim(@Param("id") Long id);

    // 완료 — 그사이 무효(EXPIRED)가 됐으면 0 이고, 호출자가 올린 객체를 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.DONE, e.sizeBytes = :size, "
            + "e.expiresAt = :expiresAt where e.id = :id and e.status = com.harucut.export.enums.ExportStatus.RUNNING")
    int complete(@Param("id") Long id, @Param("size") long size, @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.FAILED, e.failureReason = :reason "
            + "where e.id = :id and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING)")
    int fail(@Param("id") Long id, @Param("reason") String reason);

    // 무효화 — 아직 살아 있는 것(PENDING·RUNNING·DONE)만 EXPIRED 로. 이긴 쪽이 객체를 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExportJob e set e.status = com.harucut.export.enums.ExportStatus.EXPIRED where e.id = :id "
            + "and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING, "
            + "com.harucut.export.enums.ExportStatus.DONE)")
    int expire(@Param("id") Long id);

    // 이 사진을 담은 살아 있는 묶음 — 사진 삭제 때 무효로 한다
    @Query("select distinct e from ExportJob e, ExportJobItem i where i.exportJobId = e.id and i.composeJobId = :composeJobId "
            + "and e.status in (com.harucut.export.enums.ExportStatus.PENDING, com.harucut.export.enums.ExportStatus.RUNNING, "
            + "com.harucut.export.enums.ExportStatus.DONE)")
    List<ExportJob> findLiveContaining(@Param("composeJobId") Long composeJobId);
}
