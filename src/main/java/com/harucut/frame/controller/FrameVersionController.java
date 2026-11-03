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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "프레임")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/frame/{frameId}/versions")
public class FrameVersionController {

    private final FrameVersionService frameVersionService;

    @Operation(
            summary = "프레임 발행 — 불변 발행본 만들기",
            description = """
                    지금 저장된 프레임을 **수정할 수 없는 발행본**(FrameSpec v2)으로 고정한다. 행사에는 발행본을 연결한다(P2A).

                    발행본은 자산만 참조한다. key 문자열로 저장된 예전 이미지는 이때 자산으로 편입되고 검증된다(수 초 걸릴 수 있다).
                    **프레임 자체는 바뀌지 않는다** — 프레임 `version` 도 그대로다.

                    정적 경로 스티커·구운 PNG 없는 TEXT 는 합성할 수 없어 발행도 안 된다(400 `GEN-002`).
                    """)
    @ApiErrors({
            "GEN-002: 정적 경로 스티커나 구운 PNG 없는 TEXT 가 있다",
            "FRAME-003: 레이어 수 상한 초과",
            "FRAME-004: 합성 스펙 크기 초과",
            "FRAME-005: 쓸 수 없는 자산(편입 검증 실패 포함)",
            "GEN-031: 없는 프레임이거나 내 프레임이 아님"
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<FrameVersionResponse> publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "프레임 ID", example = "7") @PathVariable Long frameId) {
        return Response.ok(frameVersionService.publish(principal.publicId(), frameId));
    }

    @Operation(summary = "발행본 목록", description = "최신 발행 번호부터. `spec` 은 빠진다.")
    @ApiErrors("GEN-031: 없는 프레임이거나 내 프레임이 아님")
    @GetMapping
    public Response<List<FrameVersionResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "프레임 ID", example = "7") @PathVariable Long frameId) {
        return Response.ok(frameVersionService.list(principal.publicId(), frameId));
    }

    @Operation(
            summary = "발행본 긴급 회수",
            description = """
                    공개 행사의 `frames` 와 고르기 목록에서 빠지고, 합성 접수·발행본 조회는 403 `PART-004`, 게시는 400 `EVENT-003` 이 된다.
                    이미 접수한 작업과 결과는 그대로 두고, 다른 프레임으로 대체하지 않는다. 이미 회수했으면 처음 사유가 그대로다.
                    """)
    @ApiErrors({
            "GEN-003: 사유가 없거나 500자를 넘는다",
            "GEN-021: 이 공간에서 프레임을 고칠 수 없는 역할이다",
            "GEN-031: 없는 프레임·발행본이거나 내 프레임이 아님"
    })
    @PostMapping("/{versionNo}/revoke")
    public Response<FrameVersionResponse> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "프레임 ID", example = "7") @PathVariable Long frameId,
            @Parameter(description = "발행 번호", example = "3") @PathVariable int versionNo,
            @RequestBody @Valid FrameVersionRevokeRequest request) {
        return Response.ok(frameVersionService.revoke(principal.publicId(), frameId, versionNo, request.reason()));
    }

    @Operation(summary = "긴급 회수 해제", description = "다시 게시·접수할 수 있다. 회수 중이 아니면 그대로 답한다. 빠졌던 행사의 프레임도 돌아온다.")
    @ApiErrors({
            "GEN-021: 이 공간에서 프레임을 고칠 수 없는 역할이다",
            "GEN-031: 없는 프레임·발행본이거나 내 프레임이 아님"
    })
    @PostMapping("/{versionNo}/restore")
    public Response<FrameVersionResponse> restore(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "프레임 ID", example = "7") @PathVariable Long frameId,
            @Parameter(description = "발행 번호", example = "3") @PathVariable int versionNo) {
        return Response.ok(frameVersionService.restore(principal.publicId(), frameId, versionNo));
    }
}
