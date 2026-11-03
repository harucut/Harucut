package com.harucut.workspace.dto;

import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceKind;
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
        WorkspaceRole role,

        @Schema(description = """
                조직 공간의 종류(업체/주최자). `HOST`(기본) — 행사를 만든 OWNER·ADMIN 이 사진 권한을 받는다.
                `ENTERPRISE` — 업체 공간. 멤버는 이 공간 행사의 사진을 보지 않고 초대한 고객만 본다 —
                화면은 「이 행사 사진은 받지 않고 초대한 분이 받아요」를 보여 준다. 개인 공간은 늘 HOST""", example = "HOST")
        WorkspaceKind kind
) {
    public static WorkspaceResponse of(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(workspace.getPublicId(), workspace.getType(), workspace.getName(), role, workspace.getKind());
    }
}
