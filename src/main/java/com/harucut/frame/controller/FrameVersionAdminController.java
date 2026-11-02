package com.harucut.frame.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.dto.FrameVersionRevokeRequest;
import com.harucut.frame.service.FrameVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "시스템 프레임 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
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
    @PostMapping("/frames/{frameId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<FrameVersionResponse> publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "시스템 프레임 ID", example = "1") @PathVariable Long frameId) {
        return Response.ok(frameVersionService.publishSystem(principal.publicId(), frameId));
    }

    @Operation(summary = "시스템 프레임 발행본 목록", description = "최신 발행 번호부터. `spec` 은 빠지고, 회수된 발행본도 함께 나온다.")
    @ApiErrors("FRAME-001: 없는 시스템 프레임")
    @GetMapping("/frames/{frameId}/versions")
    public Response<List<FrameVersionResponse>> list(
            @Parameter(description = "시스템 프레임 ID", example = "1") @PathVariable Long frameId) {
        return Response.ok(frameVersionService.listSystem(frameId));
    }

    @Operation(
            summary = "발행본 긴급 회수",
            description = """
                    어느 공간의 발행본이든 시스템 발행본이든 id 로 회수한다. 공개 행사의 `frames` 와 고르기 목록에서 빠지고,
                    합성 접수·발행본 조회는 403 `PART-004`, 게시는 400 `EVENT-003` 이 된다. 이미 접수한 작업과 결과는 그대로 둔다.
                    """)
    @ApiErrors({"GEN-003: 사유가 없거나 500자를 넘는다", "GEN-031: 없는 발행본"})
    @PostMapping("/frame-versions/{frameVersionId}/revoke")
    public Response<FrameVersionResponse> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "발행본 ID", example = "41") @PathVariable Long frameVersionId,
            @RequestBody @Valid FrameVersionRevokeRequest request) {
        return Response.ok(frameVersionService.revokeByAdmin(principal.publicId(), frameVersionId, request.reason()));
    }

    @Operation(summary = "긴급 회수 해제", description = "다시 게시·접수할 수 있다. 회수 중이 아니면 그대로 답한다.")
    @ApiErrors("GEN-031: 없는 발행본")
    @PostMapping("/frame-versions/{frameVersionId}/restore")
    public Response<FrameVersionResponse> restore(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "발행본 ID", example = "41") @PathVariable Long frameVersionId) {
        return Response.ok(frameVersionService.restoreByAdmin(principal.publicId(), frameVersionId));
    }
}
