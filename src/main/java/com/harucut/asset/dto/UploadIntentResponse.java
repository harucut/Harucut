package com.harucut.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "S3 로 보낼 PUT 의 전부")
public record UploadIntentResponse(
        @Schema(description = "자산 public id — 업로드 후 complete 에 쓴다", example = "As8kLm2Pq9Xz")
        String assetId,

        @Schema(description = "presigned PUT URL")
        String uploadUrl,

        @Schema(description = "항상 PUT", example = "PUT")
        String method,

        @Schema(description = """
                **그대로** 보낼 헤더. 서명에 들어가 있어 빼거나 바꾸면 S3 가 403 이다.
                `If-None-Match: *` 는 서버 설정(storage.upload.conditional-put)이 켜져 있을 때만 있다.
                Host·Content-Length 는 브라우저가 정한다.""",
                example = "{\"Content-Type\": \"image/png\", \"If-None-Match\": \"*\"}")
        Map<String, String> headers,

        @Schema(description = "URL 만료 시각")
        Instant expiresAt
) {
}
