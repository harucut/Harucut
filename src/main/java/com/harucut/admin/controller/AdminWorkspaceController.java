package com.harucut.admin.controller;

import com.harucut.admin.dto.AdminWorkspaceDtos;
import com.harucut.admin.service.AdminWorkspaceService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "계약·이용권 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/workspaces")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWorkspaceController {

    private final AdminWorkspaceService adminWorkspaceService;

    @Operation(summary = "공간 종류 바꾸기(업체/주최자)",
            description = """
                    `HOST`(기본): 사용자가 만든 공간. 행사를 만든 OWNER·ADMIN 이 사진 권한 네 개를 자동으로 받고, 자기 초대를 자기가 수락할 수 있다.
                    `ENTERPRISE`: 업체 공간. 공간 멤버(OWNER·ADMIN·OPERATOR·DESIGNER·VIEWER)는 이 공간 행사의 사진을 보지 않는다 —
                    사진 권한 행이 있어도 판정에서 빠지고, 멤버에게 사진 권한을 주거나 멤버가 고객 초대를 수락하면 403 `MEMBER-005` 다.
                    고객 초대·사용량·찍은 수·프레임·테마·게시는 그대로 된다. 링크를 받은 고객(EventGrant)만 받은 권한대로 사진을 본다.

                    바꾸는 즉시 적용된다(요청마다 공간을 본다). 기존 행사의 사진 권한 행은 지우지 않는다 — HOST 로 되돌리면 그대로 살아난다.
                    개인 공간은 404. 감사 로그 `WORKSPACE_KIND_CHANGED`. 공간은 `GET /api/admin/workspaces?query=` 로 찾는다.
                    """)
    @ApiErrors({"GEN-031: 없는 공간이거나 개인 공간", "GEN-003: kind·reason 누락"})
    @PatchMapping("/{workspaceId}/kind")
    public Response<AdminWorkspaceDtos.KindResponse> changeKind(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                                @RequestBody @Valid AdminWorkspaceDtos.KindRequest request) {
        return Response.ok(adminWorkspaceService.changeKind(principal.publicId(), workspaceId, request.kind(), request.reason()));
    }
}
