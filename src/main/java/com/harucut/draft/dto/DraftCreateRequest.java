package com.harucut.draft.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Schema(description = "서버 초안 생성")
public record DraftCreateRequest(
        @Schema(description = "이 초안의 프레임 ID. 생략하면 새 프레임 초안", example = "7")
        Long frameId,

        @Schema(description = "공간 public id. 생략하면 개인 공간", example = "Ws1aB2cD3eF4")
        String workspaceId,

        @NotNull
        @Schema(description = """
                편집기 문서. 서버는 해석하지 않는다. 직렬화 256KB 이하(넘으면 413 `DRAFT-002`).
                사진·배경을 dataURL 로 넣지 말고 자산을 먼저 올린 뒤 assetId 로 참조한다.""",
                requiredMode = Schema.RequiredMode.REQUIRED)
        JsonNode document,

        @Schema(description = """
                document 가 쓰는 자산 id 목록. 서버가 문서를 해석하지 않으므로 이 목록이 "아직 쓰는 자산"의 근거다 —
                빠뜨리면 미참조 자산 정리 배치가 지울 수 있다. 이 공간의 VERIFIED 자산이어야 한다(아니면 400 `FRAME-005`).""")
        List<String> assetIds
) {
}
