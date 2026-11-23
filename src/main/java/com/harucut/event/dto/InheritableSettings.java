package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 행사 설정 중 상위(공간 → 프로젝트)에서 물려받을 수 있는 필드만 (ADR-0009 §2-1). 이름은 행사 설정(EventSettings)과 같다.
 * 공간·프로젝트의 브랜드 기본값, 그리고 행사가 「직접 정한 값」(event.settings_draft)이 모두 이 모양이다.
 * null(키 없음) = 이 단계에서 정하지 않음 → 위 단계 값. 테마 안의 문자열 "" = 위 값을 쓰지 않고 기본값·없음(§2-1 표).
 */
@Schema(name = "BrandDefaults", description = "상속 가능한 행사 설정 — 공간·프로젝트 기본값과 행사의 직접 값이 같은 모양이다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InheritableSettings(
        @Schema(description = "표시용 시간대", example = "Asia/Seoul") String timezone,
        @Schema(description = "개인 휴대폰 참가 허용") Boolean byodEnabled,
        @Schema(description = "현장 부스 참가 허용") Boolean boothEnabled,
        @Schema(description = "결과 보관 일수", example = "30") Integer resultRetentionDays,
        @Schema(description = "허용 레이아웃 — 목록은 통째로 대체된다") List<FrameType> allowedLayouts,
        @Schema(description = "제공 프레임 — 목록은 통째로 대체된다") List<EventSettings.FrameRef> frames,
        @Schema(description = "동의 항목 — 목록은 통째로 대체된다") List<EventSettings.ConsentItem> consentItems,
        @Schema(description = "페이지 테마 — 객체째 바꾸고, 그 안에서 빠진 키는 위 단계 값을 쓴다") PageTheme theme,
        @Schema(description = "참가자 자유 문구 — 객체째 대체. {enabled:false} 는 끔, null 은 위 단계 값") FreeTextPolicy freeText
) {
    public InheritableSettings(String timezone, Boolean byodEnabled, Boolean boothEnabled, Integer resultRetentionDays,
                               List<FrameType> allowedLayouts, List<EventSettings.FrameRef> frames,
                               List<EventSettings.ConsentItem> consentItems, PageTheme theme) {
        this(timezone, byodEnabled, boothEnabled, resultRetentionDays, allowedLayouts, frames, consentItems, theme, null);
    }

    public static InheritableSettings empty() {
        return new InheritableSettings(null, null, null, null, null, null, null, null, null);
    }
}
