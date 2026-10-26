package com.harucut.asset.repository;

import com.harucut.asset.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByPublicId(String publicId);

    Optional<Asset> findByS3Key(String s3Key);
}
