package com.harucut.workspace.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.event.converter.PhotoPermissionsConverter;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.workspace.enums.InvitationKind;
import com.harucut.workspace.enums.InvitationStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

// 공간 멤버 초대(WORKSPACE) 또는 행사 고객 초대(EVENT). 토큰 원문은 저장하지 않는다 (ADR-0008 §2)
@Entity
@Table(name = "workspace_invitation", indexes = @Index(name = "idx_workspace_invitation_workspace_id", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceInvitation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workspace_invitation_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "event_id")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationKind kind;

    @Enumerated(EnumType.STRING)
    private WorkspaceRole role;

    @Convert(converter = PhotoPermissionsConverter.class)
    @Column(length = 200)
    private Set<PhotoPermission> permissions = EnumSet.noneOf(PhotoPermission.class);

    @Column(length = 120)
    private String email;

    @Column(length = 200)
    private String note;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(name = "accepted_by_user_id")
    private Long acceptedByUserId;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    private WorkspaceInvitation(Long workspaceId, Long eventId, InvitationKind kind, WorkspaceRole role,
                                Set<PhotoPermission> permissions, String email, String note, String tokenHash,
                                LocalDateTime expiresAt, Long invitedByUserId) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.eventId = eventId;
        this.kind = kind;
        this.role = role;
        this.permissions = permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(PhotoPermission.class) : EnumSet.copyOf(permissions);
        this.email = email;
        this.note = note;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.status = InvitationStatus.OPEN;
        this.invitedByUserId = invitedByUserId;
    }

    public static WorkspaceInvitation member(Long workspaceId, WorkspaceRole role, String email, String tokenHash,
                                             LocalDateTime expiresAt, Long invitedByUserId) {
        return new WorkspaceInvitation(workspaceId, null, InvitationKind.WORKSPACE, role, null, email, null,
                tokenHash, expiresAt, invitedByUserId);
    }

    public static WorkspaceInvitation eventCustomer(Long workspaceId, Long eventId, Set<PhotoPermission> permissions,
                                                    String note, String tokenHash, LocalDateTime expiresAt,
                                                    Long invitedByUserId) {
        return new WorkspaceInvitation(workspaceId, eventId, InvitationKind.EVENT, null, permissions, null, note,
                tokenHash, expiresAt, invitedByUserId);
    }

    public boolean expiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public void revoke() {
        if (status == InvitationStatus.OPEN) {
            this.status = InvitationStatus.REVOKED;
        }
    }

    public void accepted(Long userId, LocalDateTime now) {
        this.acceptedByUserId = userId;
        this.acceptedAt = now;
    }
}
