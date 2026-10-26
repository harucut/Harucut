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
                    OWNER·ADMIN. 수락하면 공간 멤버가 아니라 이 행사의 사진 권한만 생긴다 — 공간의 다른 행사는 보이지 않는다.
                    토큰 원문은 이 응답에서만, 링크 `/invite#<token>`, 7일.
                    """)
    @ApiErrors("GEN-031: 없는 행사이거나 OWNER·ADMIN 이 아니다")
    @PostMapping("/api/auth/events/{eventId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<MemberDtos.InvitationIssued> invite(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                        @RequestBody @Valid MemberDtos.EventInvitationRequest request) {
        return Response.ok(eventGrantService.inviteCustomer(principal.publicId(), eventId, request));
    }

    @Operation(summary = "행사 사진 권한 목록", description = "OWNER·ADMIN.")
    @ApiErrors("GEN-031: 없는 행사이거나 OWNER·ADMIN 이 아니다")
    @GetMapping("/api/auth/events/{eventId}/grants")
    public Response<List<MemberDtos.EventGrantView>> grants(@AuthenticationPrincipal AuthenticatedUser principal,
                                                            @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventGrantService.grants(principal.publicId(), eventId));
    }

    @Operation(summary = "행사 사진 권한 바꾸기", description = "OWNER·ADMIN. 권한 집합을 통째로 바꾼다.")
    @ApiErrors("GEN-031: 없는 행사·권한이거나 OWNER·ADMIN 이 아니다")
    @PatchMapping("/api/auth/events/{eventId}/grants/{grantId}")
    public Response<MemberDtos.EventGrantView> update(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                      @Parameter(description = "권한 ID") @PathVariable Long grantId,
                                                      @RequestBody @Valid MemberDtos.EventGrantRequest request) {
        return Response.ok(eventGrantService.update(principal.publicId(), eventId, grantId, request.permissions()));
    }

    @Operation(summary = "행사 사진 권한 회수", description = "OWNER·ADMIN.")
    @ApiErrors("GEN-031: 없는 행사·권한이거나 OWNER·ADMIN 이 아니다")
    @DeleteMapping("/api/auth/events/{eventId}/grants/{grantId}")
    public Response<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "행사 public id") @PathVariable String eventId,
                                 @Parameter(description = "권한 ID") @PathVariable Long grantId) {
        eventGrantService.delete(principal.publicId(), eventId, grantId);
        return Response.ok();
    }

    @Operation(summary = "내가 접근할 수 있는 행사",
            description = "운영하는 공간(OWNER·ADMIN)의 행사와 사진 권한을 받은 행사. 최신순. 행사 고객에게는 Studio 의 시작점이다.")
    @GetMapping("/api/auth/me/events")
    public Response<List<MemberDtos.MyEvent>> myEvents(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Response.ok(eventGrantService.myEvents(principal.publicId()));
    }
}
