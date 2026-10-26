package com.harucut.workspace.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공간 멤버")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    @Operation(summary = "초대 미리보기",
            description = "수락 전에 무엇에 들어가는지 보여 준다. 소비하지 않는다. 토큰을 쿼리에 싣지 않으려고 POST.")
    @ApiErrors({"MEMBER-001: 기한이 지난 초대", "MEMBER-002: 틀렸거나 이미 쓴·회수된 초대"})
    @PostMapping("/preview")
    public Response<MemberDtos.InvitationPreview> preview(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @RequestBody @Valid MemberDtos.InvitationTokenRequest request) {
        return Response.ok(invitationService.preview(principal.publicId(), request.token()));
    }

    @Operation(summary = "초대 수락",
            description = """
                    1회 소비. 멤버 초대면 공간 멤버가 되고, 행사 고객 초대면 그 행사의 사진 권한만 생긴다(이미 있으면 합친다).
                    이미 같은 권한이면 토큰을 쓰지 않고 MEMBER-003.
                    """)
    @ApiErrors({"MEMBER-001: 기한이 지난 초대", "MEMBER-002: 틀렸거나 이미 쓴·회수된 초대", "MEMBER-003: 이미 같은 권한"})
    @PostMapping("/accept")
    public Response<MemberDtos.InvitationAccepted> accept(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @RequestBody @Valid MemberDtos.InvitationTokenRequest request) {
        return Response.ok(invitationService.accept(principal.publicId(), request.token()));
    }
}
