package com.harucut.draft.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Schema(description = "서버 초안 저장")
public record DraftUpdateRequest(
        @NotNull
        @Schema(description = "마지막으로 받은 revision. 서버와 다르면 409 `DRAFT-001`", example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer revision,

        @NotNull
        @Schema(description = "편집기 문서 전체(부분 저장 아님)", requiredMode = Schema.RequiredMode.REQUIRED)
        JsonNode document,

        @Schema(description = "document 가 쓰는 자산 id 목록 — 이번 저장의 목록으로 통째로 바뀐다")
        List<String> assetIds
) {
}
