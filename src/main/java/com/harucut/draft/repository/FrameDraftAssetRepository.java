package com.harucut.draft.repository;

import com.harucut.draft.entity.FrameDraftAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;

public interface FrameDraftAssetRepository extends JpaRepository<FrameDraftAsset, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FrameDraftAsset a where a.frameDraftId in :draftIds")
    void deleteByFrameDraftIdIn(Collection<Long> draftIds);
}
