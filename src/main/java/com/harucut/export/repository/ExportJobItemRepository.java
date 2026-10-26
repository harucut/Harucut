package com.harucut.export.repository;

import com.harucut.export.entity.ExportJobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExportJobItemRepository extends JpaRepository<ExportJobItem, Long> {

    List<ExportJobItem> findAllByExportJobIdOrderBySortOrderAsc(Long exportJobId);

    // 담긴 사진 중 삭제 요청된 것이 있는가 — 수령 때 다시 본다
    @Query("select count(i) > 0 from ExportJobItem i, ComposeJob j where i.exportJobId = :exportJobId "
            + "and j.id = i.composeJobId and (j.deletedAt is not null or j.purgedAt is not null)")
    boolean containsDeleted(@Param("exportJobId") Long exportJobId);
}
