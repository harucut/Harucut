package com.harucut.asset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.enums.AssetStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "자산")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetResponse(
        @Schema(description = "자산 public id", example = "As8kLm2Pq9Xz")
        String assetId,

        @Schema(description = "용도", example = "FRAME_ASSET")
        AssetPurpose purpose,

        @Schema(description = "검증 상태. VERIFIED 만 프레임·합성에 쓸 수 있다", example = "VERIFIED")
        AssetStatus status,

        @Schema(description = "MIME", example = "image/png")
        String contentType,

        @Schema(description = """
                S3 key. 편집기 컴포넌트의 `source`(배경은 `key`)에 이 값을 넣고 `assetId` 도 함께 보낸다 —
                서버는 assetId 로 key 를 정하므로 둘이 같으면 그대로 통과한다.""",
                example = "uploads/users/AbCdEf12Gh/assets/As8kLm2Pq9Xz.png")
        String key,

        @Schema(description = "실제 바이트 크기 — VERIFIED 이후")
        Long size,

        @Schema(description = "가로 픽셀 — VERIFIED 이후")
        Integer width,

        @Schema(description = "세로 픽셀 — VERIFIED 이후")
        Integer height,

        @Schema(description = "REJECTED 일 때만 — 오류 코드와 같은 값", example = "ASSET-003")
        String rejectReason,

        @Schema(description = "VERIFIED 일 때만 — 짧은 GET URL(기본 5분)")
        String url,

        @Schema(description = "url 만료 시각")
        Instant urlExpiresAt
) {
    public static AssetResponse of(Asset asset, String url, Instant urlExpiresAt) {
        return new AssetResponse(asset.getPublicId(), asset.getPurpose(), asset.getStatus(),
                asset.getContentType(), asset.getS3Key(), asset.getActualSize(), asset.getWidth(), asset.getHeight(),
                asset.getRejectReason(), url, urlExpiresAt);
    }
}
