package com.harucut.asset.entity;

import com.harucut.asset.enums.AssetOrigin;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.enums.AssetStatus;
import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 공통 이미지 자산 한 건. S3 key 는 서버가 정하고, 권한은 workspace_id 로 판정한다 (ADR-0006 §2)
@Entity
@Table(name = "asset",
        indexes = {
                @Index(name = "idx_asset_workspace_id", columnList = "workspace_id"),
                @Index(name = "idx_asset_created_at", columnList = "created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "asset_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    // NULL = SYSTEM 범위
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetOrigin origin;

    @Column(name = "s3_key", nullable = false, unique = true, length = 512)
    private String s3Key;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Column(name = "actual_size")
    private Long actualSize;

    private Integer width;

    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetStatus status;

    // REJECTED 일 때만 — 오류 코드 문자열(ASSET-00x)
    @Column(name = "reject_reason", length = 16)
    private String rejectReason;

    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    private Asset(String publicId, Long workspaceId, AssetPurpose purpose, AssetOrigin origin, String s3Key,
                  String contentType, long declaredSize, Long uploadedByUserId) {
        this.publicId = publicId;
        this.workspaceId = workspaceId;
        this.purpose = purpose;
        this.origin = origin;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.declaredSize = declaredSize;
        this.uploadedByUserId = uploadedByUserId;
        this.status = AssetStatus.PENDING_UPLOAD;
    }

    /**
     * 브라우저가 올릴 자산. key 는 public id 에서 나오므로 public id 를 먼저 정해 받는다 —
     * 호출자가 같은 값으로 key 를 만든다.
     */
    public static Asset pendingUpload(String publicId, Long workspaceId, AssetPurpose purpose, String s3Key,
                                      String contentType, long declaredSize, Long uploadedByUserId) {
        return new Asset(publicId, workspaceId, purpose, AssetOrigin.CREATOR_BROWSER, s3Key,
                contentType, declaredSize, uploadedByUserId);
    }

    public static String newPublicId() {
        return PublicIds.generate();
    }

    public void verify(long actualSize, int width, int height, LocalDateTime now) {
        requirePending();
        this.status = AssetStatus.VERIFIED;
        this.actualSize = actualSize;
        this.width = width;
        this.height = height;
        this.verifiedAt = now;
    }

    public void reject(String reason) {
        requirePending();
        this.status = AssetStatus.REJECTED;
        this.rejectReason = reason;
    }

    public boolean isVerified() {
        return status == AssetStatus.VERIFIED;
    }

    // 끝난 판정은 바꾸지 않는다 — REJECTED 를 다시 검사하려면 새 intent 를 만든다
    private void requirePending() {
        if (status != AssetStatus.PENDING_UPLOAD) {
            throw new IllegalStateException("이미 판정이 끝난 자산이다: " + publicId + " " + status);
        }
    }
}
