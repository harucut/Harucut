package com.harucut.workspace.dto;

import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.enums.WorkspaceType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "접근 가능한 공간")
public record WorkspaceResponse(
        @Schema(description = "공간 public id", example = "Ws1aB2cD3eF4")
        String workspaceId,

        @Schema(description = "공간 종류. P1 에서는 PERSONAL 하나만 나온다", example = "PERSONAL")
        WorkspaceType type,

        @Schema(description = "공간 이름", example = "내 공간")
        String name,

        @Schema(description = "이 공간에서의 내 역할", example = "OWNER")
        WorkspaceRole role
) {
    public static WorkspaceResponse of(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(workspace.getPublicId(), workspace.getType(), workspace.getName(), role);
    }
}
