package com.harucut.result.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "새로 발급한 결과 토큰 — 원문은 이 응답에서만 볼 수 있다")
public record ResultTokenResponse(
        @Schema(description = "결과 토큰. 결과 링크는 /r#<token>") String resultToken,
        @Schema(description = "토큰 만료 시각(보관 기한)") OffsetDateTime resultExpiresAt
) {
}
