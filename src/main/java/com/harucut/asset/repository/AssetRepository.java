package com.harucut.asset.repository;

import com.harucut.asset.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByPublicId(String publicId);

    Optional<Asset> findByS3Key(String s3Key);

    List<Asset> findAllByPublicIdIn(Collection<String> publicIds);

    // 이 key 들 중 자산 행이 있는 것 — 자산이 된 key 는 프레임 정리가 아니라 미참조 자산 정리 배치가 지운다
    @Query("select a.s3Key from Asset a where a.s3Key in :keys")
    List<String> findExistingKeys(Collection<String> keys);

    @Query("select a.s3Key from Asset a where a.workspaceId = :workspaceId")
    List<String> findKeysByWorkspaceId(Long workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Asset a where a.workspaceId = :workspaceId")
    void deleteByWorkspaceId(Long workspaceId);
}
