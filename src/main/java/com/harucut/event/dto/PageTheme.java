package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 페이지 테마 — EventSettings.theme 에 쓰는 모양 (ADR-0008 §5). 자유 CSS·임의 URL 은 받지 않는다:
 * 허용 목록 값과 이 공간의 자산 id 만. 형식 검사는 게시 때 위반 목록으로 모아 알려 준다.
 */
@Schema(name = "PageTheme", description = "행사 페이지 테마(설정에 쓰는 모양). 공개 응답에는 ResolvedPageTheme 이 실린다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageTheme(
        @Schema(description = "스키마 버전", example = "1", allowableValues = "1") Integer schemaVersion,
        Colors colors,
        @Schema(description = "글꼴. SYSTEM = 바꾸지 않음(FE 기본 서체 그대로 — 지금은 Pretendard). 테마가 없으면 SYSTEM 으로 채워 내려준다", allowableValues = {"SYSTEM", "PRETENDARD", "NANUM_MYEONGJO", "GOWUN_DODUM", "BLACK_HAN_SANS"},
                example = "PRETENDARD") String font,
        @Schema(description = "로고 — 이 공간의 VERIFIED PAGE_THEME_ASSET id") String logoAssetId,
        @Schema(description = "배경 이미지 — 이 공간의 VERIFIED PAGE_THEME_ASSET id") String backgroundAssetId,
        Copy copy,
        Branding branding
) {

    /** #RRGGBB. FE 토큰 --ev-bg --ev-card --ev-text --ev-muted --ev-border --ev-accent --ev-accent-contrast 와 1:1 */
    @Schema(name = "PageThemeColors", description = "#RRGGBB — FE 토큰 --ev-* 와 1:1. 빠진 색은 기본값")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Colors(
            @Schema(example = "#FFF8F3") String bg,
            @Schema(example = "#FFFFFF") String card,
            @Schema(example = "#2B2B2B") String text,
            @Schema(example = "#6B6B6B") String muted,
            @Schema(example = "#E8DED6") String border,
            @Schema(example = "#C8553D") String accent,
            @Schema(example = "#FFFFFF") String accentContrast
    ) {
    }

    /** 화면별 문구 — 각 200자 이내 일반 텍스트. 빠지면 FE 기본 문구 */
    @Schema(name = "PageThemeCopy", description = "화면별 문구, 각 200자 이내 일반 텍스트(HTML 아님). 빠지면 FE 기본 문구. 부스 대기 화면은 welcome")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Copy(String welcome, String consentIntro, String captureGuide, String composing, String done,
                       String expired, String closed, String error) {
    }

    @Schema(name = "PageThemeBranding")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Branding(
            @Schema(description = "하루컷 UI 표시 숨김 — 이용권 기능 WHITE_LABEL 이 있어야 게시된다") Boolean hideHarucutBranding,
            @Schema(description = "완성 이미지 로고 정책. P2B 는 저장·발행만(합성 반영은 P5)", allowableValues = {"NONE", "ORGANIZER_LOGO"})
            String outputLogo
    ) {
    }
}
