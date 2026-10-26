package com.harucut.frame.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 발행본 → 자산 참조. 발행본 spec JSON 을 해석하지 않고도 "이 자산이 아직 쓰이는가"를 셀 수 있게 따로 둔다
@Entity
@Table(name = "frame_version_asset",
        uniqueConstraints = @UniqueConstraint(name = "uk_frame_version_asset", columnNames = {"frame_version_id", "asset_id"}),
        indexes = @Index(name = "idx_frame_version_asset_asset_id", columnList = "asset_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FrameVersionAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_version_asset_id")
    private Long id;

    @Column(name = "frame_version_id", nullable = false)
    private Long frameVersionId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    public FrameVersionAsset(Long frameVersionId, Long assetId) {
        this.frameVersionId = frameVersionId;
        this.assetId = assetId;
    }
}
