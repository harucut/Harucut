package com.harucut.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "유효 설정 미리보기 — 게시하면 이 값이 발행본에 들어간다(ADR-0009 §2-2)")
public record EffectiveSettingsResponse(
        @Schema(description = "유효값(공간 ⊕ 프로젝트 ⊕ 행사). 테마의 \"\" 로 덮은 키는 빠진다(FE 기본값·이미지 없음)") EventSettings settings,
        @Schema(description = "값이 있는 경로(\"\" 로 덮은 경로 포함)마다 출처 — WORKSPACE·PROJECT·EVENT. 키가 없으면 어디에도 값이 없다"
                + "(시간대·참가 채널·보관 일수는 그때 서버 기본값)",
                example = "{\"theme.colors.bg\":\"PROJECT\",\"consentItems\":\"WORKSPACE\",\"name\":\"EVENT\"}") Map<String, String> sources
) {
}
