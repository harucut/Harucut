package com.harucut.frame.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.version.FrameSpecV2;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "프레임 발행본 — 수정할 수 없다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameVersionResponse(
        @Schema(description = "발행본 ID", example = "41")
        Long frameVersionId,

        @Schema(description = "원본 프레임 ID", example = "7")
        Long frameId,

        @Schema(description = "프레임별 발행 번호(1부터)", example = "3")
        int versionNo,

        @Schema(description = "레이아웃", example = "CLASSIC")
        FrameType frameType,

        @Schema(description = "발행 시각")
        LocalDateTime createdAt,

        @Schema(description = "FrameSpec v2 — 목록 조회에서는 빠진다. 자산 url 은 짧은 GET URL(기본 5분)")
        FrameSpecV2 spec
) {
    public static FrameVersionResponse summary(FrameVersion version) {
        return new FrameVersionResponse(version.getId(), version.getFrameId(), version.getVersionNo(),
                version.getFrameType(), version.getCreatedAt(), null);
    }

    public static FrameVersionResponse full(FrameVersion version, FrameSpecV2 spec) {
        return new FrameVersionResponse(version.getId(), version.getFrameId(), version.getVersionNo(),
                version.getFrameType(), version.getCreatedAt(), spec);
    }
}
