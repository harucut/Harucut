package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** 참가 화면이 그리는 테마 — 기본값을 채우고 자산 id 대신 URL 을 싣는다 (ADR-0008 §5) */
@Schema(name = "ResolvedPageTheme", description = "참가·부스·결과 화면용 테마. 색·글꼴·브랜딩은 항상 채워져 있다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvedPageTheme(
        int schemaVersion,
        PageTheme.Colors colors,
        String font,
        @Schema(description = "로고 presigned GET(24시간). 없으면 키 없음") String logoUrl,
        @Schema(description = "배경 이미지 presigned GET(24시간). 없으면 키 없음") String backgroundUrl,
        @Schema(description = "주최자가 쓴 문구만 — 빠진 화면은 FE 기본 문구") PageTheme.Copy copy,
        Branding branding
) {

    @Schema(name = "ResolvedPageThemeBranding")
    public record Branding(boolean hideHarucutBranding, String outputLogo) {
    }
}
