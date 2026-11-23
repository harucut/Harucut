package com.harucut.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.node.ObjectNode;

@Schema(description = "행사 설정 수정 — settings 에 **보낸 필드만** 바뀐다(null 을 보내면 그 값을 비운다)")
public record EventPatchRequest(
        @NotNull
        @Schema(description = "마지막으로 받은 settingsVersion. 서버와 다르면 409 `EVENT-005`", example = "2",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer settingsVersion,

        @NotNull
        @Schema(description = "바꿀 설정(EventSettings 의 일부)", implementation = EventSettings.class,
                requiredMode = Schema.RequiredMode.REQUIRED)
        ObjectNode settings
) {
}
