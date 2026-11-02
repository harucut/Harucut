package com.harucut.result.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import com.harucut.event.dto.ResolvedPageTheme;

import java.time.OffsetDateTime;

@Schema(description = "결과 링크로 본 결과 상태")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultStatusResponse(
        @Schema(description = "PENDING(준비 중) · DONE · FAILED · EXPIRED(보관 기한 지남)", example = "DONE") String status,
        @Schema(description = "행사명", example = "민지 ♥ 준호 결혼식") String eventName,
        @Schema(description = "DONE 일 때만 — 작은 미리보기(긴 변 512px)의 짧은 URL. 목록용") String thumbnailUrl,
        @Schema(description = "DONE 일 때만 — 화면에 띄울 결과 원본의 짧은 URL(파일로 받지 않고 바로 표시). 저장은 `POST /download`") String imageUrl,
        @Schema(description = "결과 링크 만료 시각(보관 기한)") OffsetDateTime expiresAt,
        @Schema(description = "이 결과가 만들어진 경험 버전의 페이지 테마 — 기본값이 채워지고 자산은 URL") ResolvedPageTheme theme
) {
}
