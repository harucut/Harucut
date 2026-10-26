package com.harucut.workspace.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "공간 멤버")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/workspaces/{workspaceId}")
public class WorkspaceMemberController {

    private final MembershipService membershipService;

    @Operation(summary = "멤버 초대",
            description = """
                    조직 공간에만. OWNER·ADMIN 이 부르고, OWNER 초대는 OWNER 만. 토큰 원문은 이 응답에서만 — 링크는 `/invite#<token>`, 7일.
                    메일을 보내지 않는다(email 은 표시용 메모). 토큰을 가진 로그인 사용자 누구나 수락할 수 있다.
                    """)
    @ApiErrors({"GEN-021: OWNER 초대를 OWNER 가 아닌 사람이", "GEN-002: 개인 공간", "GEN-031: 없는 공간이거나 OWNER·ADMIN 이 아니다"})
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<MemberDtos.InvitationIssued> invite(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                        @RequestBody @Valid MemberDtos.MemberInvitationRequest request) {
        return Response.ok(membershipService.invite(principal.publicId(), workspaceId, request));
    }

    @Operation(summary = "열린 초대 목록", description = "멤버 초대와 행사 고객 초대(kind EVENT) 모두. 토큰은 없다. 기한이 지난 것도 expiresAt 과 함께 나온다.")
    @ApiErrors("GEN-031: 없는 공간이거나 OWNER·ADMIN 이 아니다")
    @GetMapping("/invitations")
    public Response<List<MemberDtos.Invitation>> invitations(@AuthenticationPrincipal AuthenticatedUser principal,
                                                             @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(membershipService.invitations(principal.publicId(), workspaceId));
    }

    @Operation(summary = "초대 회수")
    @ApiErrors("GEN-031: 없는 초대이거나 OWNER·ADMIN 이 아니다")
    @PostMapping("/invitations/{invitationId}/revoke")
    public Response<Void> revokeInvitation(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                           @Parameter(description = "초대 public id") @PathVariable String invitationId) {
        membershipService.revokeInvitation(principal.publicId(), workspaceId, invitationId);
        return Response.ok();
    }

    @Operation(summary = "멤버 목록", description = "멤버 누구나.")
    @ApiErrors("GEN-031: 없는 공간이거나 멤버가 아니다")
    @GetMapping("/members")
    public Response<List<MemberDtos.Member>> members(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(membershipService.members(principal.publicId(), workspaceId));
    }

    @Operation(summary = "역할 변경", description = "OWNER·ADMIN. OWNER 로 올리거나 OWNER 를 바꾸는 일은 OWNER 만. 마지막 OWNER 는 내릴 수 없다.")
    @ApiErrors({"MEMBER-004: 마지막 OWNER", "GEN-021: OWNER 관련 변경을 OWNER 가 아닌 사람이", "GEN-031: 없는 공간·멤버"})
    @PatchMapping("/members/{userId}")
    public Response<MemberDtos.Member> changeRole(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                  @Parameter(description = "사용자 public id") @PathVariable String userId,
                                                  @RequestBody @Valid MemberDtos.MemberRoleRequest request) {
        return Response.ok(membershipService.changeRole(principal.publicId(), workspaceId, userId, request.role()));
    }

    @Operation(summary = "멤버 회수·본인 탈퇴",
            description = "userId 자리에 `me` 면 본인 탈퇴(역할 무관). 그 사람의 이 공간 행사 사진 권한도 함께 회수한다. 마지막 OWNER 는 나갈 수 없다.")
    @ApiErrors({"MEMBER-004: 마지막 OWNER", "GEN-021: OWNER 를 OWNER 가 아닌 사람이", "GEN-031: 없는 공간·멤버"})
    @DeleteMapping("/members/{userId}")
    public Response<Void> remove(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                 @Parameter(description = "사용자 public id 또는 me") @PathVariable String userId) {
        membershipService.remove(principal.publicId(), workspaceId, userId);
        return Response.ok();
    }
}
