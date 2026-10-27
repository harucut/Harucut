package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.frame.enums.FrameType;

import java.util.List;

/**
 * 행사 설정 중 "참가 경험" 부분의 JSON 문서 — event.settings_draft(초안)와 event_experience_version.settings_json(발행본)이
 * 같은 모양을 쓴다. 발행본에는 editPolicy 가 채워진다 — 자유 문구가 켜졌으면 FREE_TEXT, 아니면 FIXED(R5 D06).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventExperienceDocument(
        List<FrameType> allowedLayouts,
        List<EventSettings.FrameRef> frames,
        List<EventSettings.ConsentItem> consentItems,
        PageTheme theme,
        String editPolicy,
        FreeTextPolicy freeText
) {
    public static final String FIXED = "FIXED";
    public static final String FREE_TEXT = "FREE_TEXT";

    public EventExperienceDocument(List<FrameType> allowedLayouts, List<EventSettings.FrameRef> frames,
                                   List<EventSettings.ConsentItem> consentItems, PageTheme theme, String editPolicy) {
        this(allowedLayouts, frames, consentItems, theme, editPolicy, null);
    }

    public static EventExperienceDocument empty() {
        return new EventExperienceDocument(List.of(), List.of(), List.of(), null, null, null);
    }

    // 발행본에는 켜진 정책만 남긴다 — 끈 정책({enabled:false})은 FIXED 와 같다
    public EventExperienceDocument published() {
        boolean free = freeText != null && freeText.on();
        return new EventExperienceDocument(allowedLayouts, frames, consentItems, theme, free ? FREE_TEXT : FIXED,
                free ? freeText : null);
    }

    /** 참가자가 쓸 수 있는 자유 문구 정책 — 발행본이 FREE_TEXT 일 때만 */
    public java.util.Optional<FreeTextPolicy> offeredFreeText() {
        return FREE_TEXT.equals(editPolicy) && freeText != null && freeText.on()
                ? java.util.Optional.of(freeText) : java.util.Optional.empty();
    }
}
