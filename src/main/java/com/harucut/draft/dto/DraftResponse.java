package com.harucut.draft.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.draft.entity.FrameDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

@Schema(description = "서버 초안")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DraftResponse(
        @Schema(description = "초안 public id", example = "Dr3nQ7wE1tYu")
        String draftId,

        @Schema(description = "프레임 ID. 새 프레임 초안이면 null", example = "7")
        Long frameId,

        @Schema(description = "다음 저장에 그대로 보낼 revision", example = "4")
        int revision,

        @Schema(description = "마지막 저장 시각")
        LocalDateTime updatedAt,

        @Schema(description = "편집기 문서 — 목록 조회에서는 빠진다")
        JsonNode document
) {
    public static DraftResponse summary(FrameDraft draft) {
        return new DraftResponse(draft.getPublicId(), draft.getFrameId(), draft.getRevision(), draft.getUpdatedAt(), null);
    }

    public static DraftResponse full(FrameDraft draft, JsonNode document) {
        return new DraftResponse(draft.getPublicId(), draft.getFrameId(), draft.getRevision(), draft.getUpdatedAt(), document);
    }
}
