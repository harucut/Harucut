package com.harucut.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "프레임 발행본 긴급 회수 — 사유는 감사에 남는다")
public record FrameVersionRevokeRequest(
        @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "저작권 문의") String reason) {
}
