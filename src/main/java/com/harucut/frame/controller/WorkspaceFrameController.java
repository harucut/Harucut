package com.harucut.frame.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.dto.PickableFrameVersion;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "공간")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/workspaces/{workspaceId}")
public class WorkspaceFrameController {

    private final FrameService frameService;
    private final FrameVersionService frameVersionService;

    @Operation(
            summary = "공간의 프레임 목록",
            description = """
                    조직 공간에 만든 프레임 전부(최신순) — 만든 사람과 무관하다. 멤버면 누구나 본다.
                    단건 조회·수정·삭제·발행은 기존 `/api/auth/user/frame/{frameId}` 경로를 그대로 쓴다
                    (조회는 멤버, 수정·삭제·발행은 OWNER·ADMIN·DESIGNER).
                    조직 프레임은 개인 목록(`GET /api/auth/user/frame`)과 개인 요금제 한도에 들어가지 않는다.
                    """)
    @ApiErrors("GEN-031: 없는 공간이거나 멤버가 아니다")
    @GetMapping("/frames")
    public Response<List<FrameResponse>> frames(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(frameService.getWorkspaceFrames(principal.publicId(), workspaceId));
    }

    @Operation(
            summary = "행사에 연결할 수 있는 프레임 발행본",
            description = """
                    이 공간 프레임과 시스템 프레임의 **최신 발행본**(공간 프레임 먼저, 각각 최신순). 발행 안 된 프레임은 나오지 않는다 —
                    프레임 편집기에서 발행(`POST /api/auth/user/frame/{frameId}/versions`)하면 나타난다.
                    `frameVersionId` 를 `EventSettings.frames[].frameVersionId` 에, `frameType` 을 `layoutType` 에 넣는다.
                    """)
    @ApiErrors("GEN-031: 없는 공간이거나 멤버가 아니다")
    @GetMapping("/frame-versions")
    public Response<List<PickableFrameVersion>> pickable(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(frameVersionService.pickable(principal.publicId(), workspaceId));
    }
}
