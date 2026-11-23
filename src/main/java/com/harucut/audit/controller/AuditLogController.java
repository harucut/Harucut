package com.harucut.audit.controller;

import com.harucut.audit.dto.AuditLogPage;
import com.harucut.audit.service.AuditLogService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.user.repository.UserRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;

@Tag(name = "공간 멤버")
@RestController
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;

    @Operation(summary = "공간 감사 로그", description = "OWNER·ADMIN. 최신순 50개씩, nextCursor 로 이어 받는다.")
    @ApiErrors({"GEN-002: 잘못된 커서", "GEN-031: 없는 공간이거나 OWNER·ADMIN 이 아니다"})
    @GetMapping("/api/auth/workspaces/{workspaceId}/audit-logs")
    public Response<AuditLogPage> workspace(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor) {
        Long userId = userRepository.findByPublicId(principal.publicId())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND)).getId();
        Long id = workspaceService.requireRole(workspaceId, userId, EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN)).getId();
        return Response.ok(auditLogService.workspacePage(id, cursor));
    }
}
