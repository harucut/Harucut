package com.harucut.export.repository;

import com.harucut.export.entity.ExportJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ExportJobItemRepository extends JpaRepository<ExportJobItem, Long> {

    List<ExportJobItem> findAllByExportJobIdOrderBySortOrderAsc(Long exportJobId);

    // 담긴 사진 중 삭제 요청됐거나 보관 기한이 지난 것이 있는가 — 수령 때 다시 본다(정리 배치 전이어도 같은 판정)
    @Query("select count(i) > 0 from ExportJobItem i, ComposeJob j where i.exportJobId = :exportJobId "
            + "and j.id = i.composeJobId and (j.deletedAt is not null or j.purgedAt is not null "
            + "or (j.retainUntil is not null and j.retainUntil <= :now))")
    boolean containsUnavailable(@Param("exportJobId") Long exportJobId, @Param("now") LocalDateTime now);

    // 담긴 사진의 행사별 장수 — [eventId, count]
    @Query("select j.eventId, count(i) from ExportJobItem i, ComposeJob j where i.exportJobId = :exportJobId "
            + "and j.id = i.composeJobId group by j.eventId")
    List<Object[]> countByEvent(@Param("exportJobId") Long exportJobId);
}
