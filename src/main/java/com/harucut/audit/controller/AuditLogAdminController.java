package com.harucut.audit.controller;

import com.harucut.audit.dto.AuditLogPage;
import com.harucut.audit.service.AuditLogService;
import com.harucut.common.response.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "감사 로그 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogAdminController {

    private final AuditLogService auditLogService;

    @Operation(summary = "전체 감사 로그", description = "플랫폼 ADMIN. 최신순 50개씩.")
    @GetMapping
    public Response<AuditLogPage> list(@Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor) {
        return Response.ok(auditLogService.platformPage(cursor));
    }
}
