package com.harucut.frame.repository;

import com.harucut.frame.entity.FrameVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FrameVersionRepository extends JpaRepository<FrameVersion, Long> {

    List<FrameVersion> findAllByFrameIdOrderByVersionNoDesc(Long frameId);

    Optional<FrameVersion> findByFrameIdAndVersionNo(Long frameId, int versionNo);

    @Query("select coalesce(max(v.versionNo), 0) from FrameVersion v where v.frameId = :frameId")
    int findMaxVersionNo(Long frameId);

    @Query("select v.id from FrameVersion v where v.frameId in :frameIds")
    List<Long> findIdsByFrameIdIn(Collection<Long> frameIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FrameVersion v where v.id in :ids")
    void deleteByIdIn(Collection<Long> ids);
}
