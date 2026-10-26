package com.harucut.result.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "짧은 다운로드 URL")
public record DownloadUrlResponse(
        @Schema(description = "presigned GET URL(기본 5분)") String url,
        @Schema(description = "URL 만료 시각") OffsetDateTime expiresAt
) {
}
