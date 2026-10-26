package com.harucut.frame.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.service.FrameVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "시스템 프레임 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/frames/{frameId}/versions")
@PreAuthorize("hasRole('ADMIN')")
public class FrameVersionAdminController {

    private final FrameVersionService frameVersionService;

    @Operation(summary = "시스템 프레임 발행", description = "사용자 발행과 같다. 편입 자산은 SYSTEM 범위가 된다.")
    @ApiErrors({
            "FRAME-001: 없는 시스템 프레임",
            "GEN-002: 정적 경로 스티커나 구운 PNG 없는 TEXT 가 있다",
            "FRAME-003: 레이어 수 상한 초과",
            "FRAME-004: 합성 스펙 크기 초과",
            "FRAME-005: 쓸 수 없는 자산(편입 검증 실패 포함)"
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<FrameVersionResponse> publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "시스템 프레임 ID", example = "1") @PathVariable Long frameId) {
        return Response.ok(frameVersionService.publishSystem(principal.publicId(), frameId));
    }
}
