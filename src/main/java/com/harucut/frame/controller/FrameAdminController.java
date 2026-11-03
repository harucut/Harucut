package com.harucut.frame.controller;

import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.service.FrameAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "시스템 프레임 관리")
@RestController
@RequestMapping("/api/admin/frames")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class FrameAdminController {

    private final FrameAdminService frameAdminService;

    @Operation(
            summary = "시스템 프레임 생성",
            description = """
                    모든 사용자에게 기본 제공되는 프레임을 만든다. 소유자가 없고,
                    **요금제 한도·보관 기간을 받지 않는다** — BASIC 사용자에게도 항상 보인다.

                    요청 본문은 사용자 프레임과 완전히 같다.
                    """)
    @ApiErrors("GEN-006: background.type 이 COLOR·IMAGE 가 아님")
    @PostMapping
    public Response<FrameResponse> createSystemFrame(@RequestBody @Valid FrameCreateRequest request) {
        return Response.ok(frameAdminService.createSystemFrame(request));
    }

    @Operation(
            summary = "시스템 프레임 목록",
            description = "시스템 프레임 전량을 최신순으로. 사용자 목록 API 뒤쪽에 붙는 것과 같은 집합이다.")
    @GetMapping
    public Response<List<FrameResponse>> listSystemFrames() {
        return Response.ok(frameAdminService.listSystemFrames());
    }

    // 수정·삭제는 없다(2026-11-03 정리) — CMS 가 부르지 않았다. 잘못 만든 시스템 프레임은 발행본 회수(frame-versions revoke)로 막고 새로 만든다
}
