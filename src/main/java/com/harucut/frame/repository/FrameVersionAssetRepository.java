package com.harucut.frame.repository;

import com.harucut.frame.entity.FrameVersionAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;

public interface FrameVersionAssetRepository extends JpaRepository<FrameVersionAsset, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FrameVersionAsset a where a.frameVersionId in :versionIds")
    void deleteByFrameVersionIdIn(Collection<Long> versionIds);
}
