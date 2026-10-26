package com.harucut.workspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.workspace.enums.InvitationKind;
import com.harucut.workspace.enums.InvitationStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

// 멤버·초대 API 의 요청·응답 (ADR-0008 §2). 스키마 이름이 다른 DTO 와 겹치지 않게 접두어를 붙인다
public final class MemberDtos {

    private MemberDtos() {
    }

    @Schema(name = "MemberInvitationRequest", description = "공간 멤버 초대")
    public record MemberInvitationRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DESIGNER") WorkspaceRole role,
            @Email @Size(max = 120) @Schema(description = "표시용 메모 — 메일을 보내지 않는다") String email
    ) {
    }

    @Schema(name = "EventInvitationRequest", description = "행사 고객 초대 — 수락하면 공간 멤버가 아니라 이 행사 권한만 생긴다")
    public record EventInvitationRequest(
            @NotEmpty @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<PhotoPermission> permissions,
            @Size(max = 200) @Schema(description = "운영자 메모(예: 신랑신부)") String note
    ) {
    }

    @Schema(name = "InvitationIssued", description = "발급한 초대 — 토큰 원문은 이 응답에서만. 링크 /invite#<token>")
    public record InvitationIssued(String invitationId, String inviteToken, OffsetDateTime expiresAt) {
    }

    @Schema(name = "Invitation", description = "열린 초대 — 토큰은 없다")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Invitation(String invitationId, InvitationKind kind, String eventId, WorkspaceRole role,
                             Set<PhotoPermission> permissions, String email, String note, OffsetDateTime expiresAt,
                             InvitationStatus status) {
    }

    @Schema(name = "InvitationTokenRequest")
    public record InvitationTokenRequest(
            @NotBlank @Size(max = 100) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token
    ) {
    }

    @Schema(name = "InvitationPreview", description = "수락 전에 보여 줄 내용 — 소비하지 않는다")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationPreview(InvitationKind kind, String workspaceName, String eventName, WorkspaceRole role,
                                    Set<PhotoPermission> permissions, String invitedByName, OffsetDateTime expiresAt) {
    }

    @Schema(name = "InvitationAccepted")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationAccepted(InvitationKind kind, String workspaceId, String eventId, WorkspaceRole role,
                                     Set<PhotoPermission> permissions) {
    }

    @Schema(name = "Member")
    public record Member(@Schema(description = "사용자 public id") String userId, String name, WorkspaceRole role,
                         OffsetDateTime joinedAt) {
    }

    @Schema(name = "MemberRoleRequest")
    public record MemberRoleRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkspaceRole role) {
    }

    @Schema(name = "EventGrant", description = "행사 권한 — 사진 권한과(OPERATOR 에게 배정한) 운영 권한")
    public record EventGrantView(Long grantId, @Schema(description = "사용자 public id") String userId, String name,
                                 Set<PhotoPermission> permissions,
                                 @Schema(description = "운영 권한 — OPERATOR 에게 배정한 것. 행사 고객은 빈 목록") Set<OperationPermission> operations,
                                 OffsetDateTime createdAt) {
    }

    @Schema(name = "OperatorAssignmentRequest", description = "행사 운영자 배정 — 빈 목록이면 운영 권한을 뺀다")
    public record OperatorAssignmentRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"MANAGE_EVENT\",\"MANAGE_BOOTH\"]")
            Set<OperationPermission> operations) {
    }

    @Schema(name = "EventGrantRequest")
    public record EventGrantRequest(@NotEmpty @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<PhotoPermission> permissions) {
    }

    @Schema(name = "MyEvent", description = "내가 접근할 수 있는 행사")
    public record MyEvent(String eventId, String name, EventStatus status, Access access) {

        @Schema(name = "MyEventAccess", description = "role 은 공간 멤버(OWNER·ADMIN·OPERATOR 등)일 때만. FE 는 operations 로만 버튼을 정한다")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Access(WorkspaceRole role, Set<PhotoPermission> permissions,
                             @Schema(description = "합쳐진 운영 권한 — OWNER·ADMIN 은 셋 다") Set<OperationPermission> operations,
                             @Schema(description = "OPERATOR 일 때만 — 권한마다 EVENT·PROJECT·BOTH. PROJECT·BOTH 는 행사 화면에서 뺄 수 없다",
                                     example = "{\"MANAGE_EVENT\":\"PROJECT\",\"VIEW_PHOTOS\":\"EVENT\"}")
                             Map<String, String> sources) {
        }
    }
}
