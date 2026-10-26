package com.harucut.event.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.event.service.EventGrantService;
import com.harucut.workspace.dto.MemberDtos;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "행사 고객·권한")
@RestController
@RequiredArgsConstructor
public class EventGrantController {

    private final EventGrantService eventGrantService;

    @Operation(summary = "행사 고객 초대",
            description = """
                    INVITE_CUSTOMERS 권한(공간 OWNER·ADMIN 은 늘 가진다). OPERATOR 는 자기가 가진 사진 권한 안에서만 준다.
                    수락하면 공간 멤버가 아니라 이 행사의 사진 권한만 생긴다 — 공간의 다른 행사는 보이지 않는다.
                    토큰 원문은 이 응답에서만, 링크 `/invite#<token>`, 7일.
                    """)
    @ApiErrors({"GEN-031: 없는 행사이거나 볼 수 없다", "GEN-021: INVITE_CUSTOMERS 가 없거나 자기 사진 권한 밖"})
    @PostMapping("/api/auth/events/{eventId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<MemberDtos.InvitationIssued> invite(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                        @RequestBody @Valid MemberDtos.EventInvitationRequest request) {
        return Response.ok(eventGrantService.inviteCustomer(principal.publicId(), eventId, request));
    }

    @Operation(summary = "행사 권한 목록",
            description = "INVITE_CUSTOMERS 권한. 사진 권한(permissions)과 OPERATOR 에게 배정한 운영 권한(operations)이 한 행에 같이 나온다.")
    @ApiErrors({"GEN-031: 없는 행사이거나 볼 수 없다", "GEN-021: INVITE_CUSTOMERS 가 없다"})
    @GetMapping("/api/auth/events/{eventId}/grants")
    public Response<List<MemberDtos.EventGrantView>> grants(@AuthenticationPrincipal AuthenticatedUser principal,
                                                            @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventGrantService.grants(principal.publicId(), eventId));
    }

    @Operation(summary = "행사 사진 권한 바꾸기",
            description = "INVITE_CUSTOMERS 권한. 사진 권한 집합을 통째로 바꾼다. OPERATOR 는 자기 사진 권한 안에서만, 다른 운영자의 행은 못 바꾼다.")
    @ApiErrors({"GEN-031: 없는 행사·권한이거나 볼 수 없다", "GEN-021: 권한 밖"})
    @PatchMapping("/api/auth/events/{eventId}/grants/{grantId}")
    public Response<MemberDtos.EventGrantView> update(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                      @Parameter(description = "권한 ID") @PathVariable Long grantId,
                                                      @RequestBody @Valid MemberDtos.EventGrantRequest request) {
        return Response.ok(eventGrantService.update(principal.publicId(), eventId, grantId, request.permissions()));
    }

    @Operation(summary = "행사 사진 권한 회수", description = "INVITE_CUSTOMERS 권한. OPERATOR 는 다른 운영자의 행을 지울 수 없다.")
    @ApiErrors({"GEN-031: 없는 행사·권한이거나 볼 수 없다", "GEN-021: 권한 밖"})
    @DeleteMapping("/api/auth/events/{eventId}/grants/{grantId}")
    public Response<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "행사 public id") @PathVariable String eventId,
                                 @Parameter(description = "권한 ID") @PathVariable Long grantId) {
        eventGrantService.delete(principal.publicId(), eventId, grantId);
        return Response.ok();
    }

    @Operation(summary = "행사 운영자 배정",
            description = """
                    공간 OWNER·ADMIN 만. 대상은 이 공간의 OPERATOR 멤버. 운영 권한 집합을 통째로 바꾸고, 빈 목록이면 뺀다.
                    사진 권한은 고객 초대·권한 수정으로 따로 준다. 프로젝트 멤버 권한과는 판정 때 합쳐진다.
                    """)
    @ApiErrors({"GEN-031: 없는 행사·사용자이거나 볼 수 없다", "GEN-021: OWNER·ADMIN 이 아니다", "GEN-002: 대상이 OPERATOR 멤버가 아니다"})
    @PutMapping("/api/auth/events/{eventId}/operators/{userId}")
    public Response<MemberDtos.EventGrantView> assignOperator(@AuthenticationPrincipal AuthenticatedUser principal,
                                                              @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                              @Parameter(description = "사용자 public id") @PathVariable String userId,
                                                              @RequestBody @Valid MemberDtos.OperatorAssignmentRequest request) {
        return Response.ok(eventGrantService.assignOperator(principal.publicId(), eventId, userId, request.operations()));
    }

    @Operation(summary = "내가 접근할 수 있는 행사",
            description = """
                    운영하는 공간(OWNER·ADMIN)의 행사, OPERATOR 로 운영 권한(행사·프로젝트)을 받은 행사, 사진 권한을 받은 행사. 최신순.
                    access.operations 로 운영 버튼을, permissions 로 사진 버튼을 정한다. OPERATOR 는 sources 로 권한마다 출처(EVENT·PROJECT·BOTH)를 준다.
                    """)
    @GetMapping("/api/auth/me/events")
    public Response<List<MemberDtos.MyEvent>> myEvents(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Response.ok(eventGrantService.myEvents(principal.publicId()));
    }
}
