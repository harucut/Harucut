package com.harucut.event.dto;

import com.harucut.text.TextStyle;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "자유 문구 스타일 — 글꼴은 Pretendard, 가운데 정렬, 크기는 서버가 칸에 맞춘다")
public record TextStyleResponse(
        @Schema(example = "pretendard-bold-dark") String id,
        @Schema(example = "굵게 · 검정") String label,
        @Schema(description = "글꼴 굵기", example = "700") int weight,
        @Schema(example = "#111111") String color
) {
    public static TextStyleResponse of(TextStyle style) {
        return new TextStyleResponse(style.id(), style.label(), style.weight().value(), style.color());
    }
}
