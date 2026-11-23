package com.harucut.admin.dto;

import com.harucut.workspace.enums.WorkspaceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AdminWorkspaceDtos {

    private AdminWorkspaceDtos() {
    }

    @Schema(name = "AdminWorkspaceKindRequest")
    public record KindRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    `HOST`(기본) — 행사를 만든 OWNER·ADMIN 이 사진 권한을 받는다. `ENTERPRISE` — 공간 멤버는 사진을 보지 않고 고객만 본다""",
                    example = "ENTERPRISE") WorkspaceKind kind,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "사유 — 감사 로그에 남는다",
                    example = "○○웨딩홀 계약") String reason
    ) {
    }

    @Schema(name = "AdminWorkspaceKindResponse")
    public record KindResponse(@Schema(description = "공간 public id") String workspaceId, WorkspaceKind kind) {
    }
}
