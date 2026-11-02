package com.harucut.result.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결과 링크 회수 결과")
public record ResultAccessRevokeResponse(
        @Schema(description = "이번에 회수한 링크 수. 이미 회수했으면 0", example = "2") int revokedCount) {
}
