package com.harucut.draft.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "frame_draft_asset",
        uniqueConstraints = @UniqueConstraint(name = "uk_frame_draft_asset", columnNames = {"frame_draft_id", "asset_id"}),
        indexes = @Index(name = "idx_frame_draft_asset_asset_id", columnList = "asset_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FrameDraftAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_draft_asset_id")
    private Long id;

    @Column(name = "frame_draft_id", nullable = false)
    private Long frameDraftId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    public FrameDraftAsset(Long frameDraftId, Long assetId) {
        this.frameDraftId = frameDraftId;
        this.assetId = assetId;
    }
}
