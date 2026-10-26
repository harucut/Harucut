package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.frame.enums.FrameType;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 행사 설정 중 "참가 경험" 부분의 JSON 문서 — event.settings_draft(초안)와 event_experience_version.settings_json(발행본)이
 * 같은 모양을 쓴다. 발행본에는 editPolicy 가 채워진다(P2A 는 FIXED 고정).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventExperienceDocument(
        List<FrameType> allowedLayouts,
        List<EventSettings.FrameRef> frames,
        List<EventSettings.ConsentItem> consentItems,
        JsonNode theme,
        String editPolicy
) {
    public static EventExperienceDocument empty() {
        return new EventExperienceDocument(List.of(), List.of(), List.of(), null, null);
    }

    public EventExperienceDocument published() {
        return new EventExperienceDocument(allowedLayouts, frames, consentItems, theme, "FIXED");
    }
}
