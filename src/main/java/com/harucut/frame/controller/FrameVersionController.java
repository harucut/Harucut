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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @Operation(summary = "발행본 하나", description = "`spec` 의 자산 `url` 은 짧은 GET URL(기본 5분)이다.")
    @ApiErrors("GEN-031: 없는 프레임·발행본이거나 내 프레임이 아님")
    @GetMapping("/{versionNo}")
    public Response<FrameVersionResponse> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "프레임 ID", example = "7") @PathVariable Long frameId,
            @Parameter(description = "발행 번호", example = "3") @PathVariable int versionNo) {
        return Response.ok(frameVersionService.get(principal.publicId(), frameId, versionNo));
    }
}
