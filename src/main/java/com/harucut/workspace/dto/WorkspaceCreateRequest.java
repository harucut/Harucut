package com.harucut.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "조직 공간 만들기")
public record WorkspaceCreateRequest(
        @NotBlank @Size(max = 100)
        @Schema(description = "공간 이름", example = "하루컷 웨딩팀", requiredMode = Schema.RequiredMode.REQUIRED)
        String name
) {
}
