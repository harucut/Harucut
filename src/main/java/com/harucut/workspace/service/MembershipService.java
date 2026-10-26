package com.harucut.workspace.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceInvitation;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.InvitationStatus;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceInvitationRepository;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 조직 공간의 멤버 초대·역할 변경·회수 (ADR-0008 §2-1). 개인 공간에는 멤버를 들이지 않는다.
 * OWNER 를 만들거나 바꾸는 일은 OWNER 만, 마지막 OWNER 는 내리거나 내보낼 수 없다.
 */
@Service
@Transactional
public class MembershipService {

    private static final Set<WorkspaceRole> OPERATOR_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
    private static final Set<WorkspaceRole> ANY_ROLE = EnumSet.allOf(WorkspaceRole.class);

    private final WorkspaceService workspaceService;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final EventGrantRepository grantRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Duration invitationTtl;

    public MembershipService(WorkspaceService workspaceService, WorkspaceMembershipRepository membershipRepository,
                             WorkspaceInvitationRepository invitationRepository, EventGrantRepository grantRepository,
                             EventRepository eventRepository, UserRepository userRepository,
                             AuditLogService auditLogService, Clock clock,
                             @Value("${workspace.invitation-ttl:7d}") Duration invitationTtl) {
        this.workspaceService = workspaceService;
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.grantRepository = grantRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.invitationTtl = invitationTtl;
    }

    public MemberDtos.InvitationIssued invite(String actorPublicId, String workspacePublicId,
                                              MemberDtos.MemberInvitationRequest request) {
        User actor = user(actorPublicId);
        Workspace workspace = organization(workspacePublicId, actor, OPERATOR_ROLES);
        if (request.role() == WorkspaceRole.OWNER) {
            requireOwner(workspace, actor);
        }
        String token = OpaqueTokens.generate();
        LocalDateTime expiresAt = now().plus(invitationTtl);
        WorkspaceInvitation invitation = invitationRepository.save(WorkspaceInvitation.member(workspace.getId(),
                request.role(), blankToNull(request.email()), OpaqueTokens.hash(token), expiresAt, actor.getId()));
        auditLogService.record(actor.getId(), workspace.getId(), null, "MEMBER_INVITED", "INVITATION",
                invitation.getPublicId(), Map.of("role", request.role().name()));
        return new MemberDtos.InvitationIssued(invitation.getPublicId(), token, offset(expiresAt));
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.Invitation> invitations(String actorPublicId, String workspacePublicId) {
        User actor = user(actorPublicId);
        Workspace workspace = organization(workspacePublicId, actor, OPERATOR_ROLES);
        List<WorkspaceInvitation> open = invitationRepository
                .findAllByWorkspaceIdAndStatusOrderByIdDesc(workspace.getId(), InvitationStatus.OPEN);
        Map<Long, String> eventIds = eventRepository.findAllById(open.stream().map(WorkspaceInvitation::getEventId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(e -> e.getId(), e -> e.getPublicId()));
        return open.stream()
                .map(invitation -> new MemberDtos.Invitation(invitation.getPublicId(), invitation.getKind(),
                        invitation.getEventId() == null ? null : eventIds.get(invitation.getEventId()),
                        invitation.getRole(), invitation.getPermissions().isEmpty() ? null : invitation.getPermissions(),
                        invitation.getEmail(), invitation.getNote(), offset(invitation.getExpiresAt()), invitation.getStatus()))
                .toList();
    }

    public void revokeInvitation(String actorPublicId, String workspacePublicId, String invitationPublicId) {
        User actor = user(actorPublicId);
        Workspace workspace = organization(workspacePublicId, actor, OPERATOR_ROLES);
        WorkspaceInvitation invitation = invitationRepository.findByPublicId(invitationPublicId)
                .filter(found -> found.getWorkspaceId().equals(workspace.getId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        invitation.revoke();
        auditLogService.record(actor.getId(), workspace.getId(), invitation.getEventId(), "INVITATION_REVOKED",
                "INVITATION", invitation.getPublicId(), null);
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.Member> members(String actorPublicId, String workspacePublicId) {
        User actor = user(actorPublicId);
        Workspace workspace = workspaceService.requireRole(workspacePublicId, actor.getId(), ANY_ROLE);
        List<WorkspaceMembership> memberships =
                membershipRepository.findAllByWorkspaceIdAndStatusOrderByIdAsc(workspace.getId(), MembershipStatus.ACTIVE);
        Map<Long, User> users = userRepository.findAllById(memberships.stream().map(WorkspaceMembership::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return memberships.stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> new MemberDtos.Member(users.get(m.getUserId()).getPublicId(), users.get(m.getUserId()).getUsername(),
                        m.getRole(), offset(m.getCreatedAt())))
                .toList();
    }

    public MemberDtos.Member changeRole(String actorPublicId, String workspacePublicId, String targetPublicId,
                                        WorkspaceRole role) {
        User actor = user(actorPublicId);
        Workspace workspace = organization(workspacePublicId, actor, OPERATOR_ROLES);
        User target = user(targetPublicId);
        WorkspaceMembership membership = activeMembership(workspace, target);
        if (membership.getRole() == WorkspaceRole.OWNER || role == WorkspaceRole.OWNER) {
            requireOwner(workspace, actor);
        }
        if (membership.getRole() == WorkspaceRole.OWNER && role != WorkspaceRole.OWNER) {
            requireAnotherOwner(workspace);
        }
        WorkspaceRole before = membership.getRole();
        membership.changeRole(role);
        auditLogService.record(actor.getId(), workspace.getId(), null, "MEMBER_ROLE_CHANGED", "USER",
                target.getPublicId(), Map.of("from", before.name(), "to", role.name()));
        return new MemberDtos.Member(target.getPublicId(), target.getUsername(), role, offset(membership.getCreatedAt()));
    }

    /** 멤버 회수. targetPublicId 가 "me" 면 본인 탈퇴. 그 사람의 이 공간 행사 권한도 함께 회수한다 */
    public void remove(String actorPublicId, String workspacePublicId, String targetPublicId) {
        User actor = user(actorPublicId);
        boolean self = "me".equals(targetPublicId) || actor.getPublicId().equals(targetPublicId);
        Workspace workspace = organization(workspacePublicId, actor, self ? ANY_ROLE : OPERATOR_ROLES);
        User target = self ? actor : user(targetPublicId);
        WorkspaceMembership membership = activeMembership(workspace, target);
        if (membership.getRole() == WorkspaceRole.OWNER) {
            if (!self) {
                requireOwner(workspace, actor);
            }
            requireAnotherOwner(workspace);
        }
        membership.remove();
        grantRepository.deleteByUserIdInWorkspace(target.getId(), workspace.getId());
        auditLogService.record(actor.getId(), workspace.getId(), null, self ? "MEMBER_LEFT" : "MEMBER_REMOVED", "USER",
                target.getPublicId(), Map.of("role", membership.getRole().name()));
    }

    // ── 관문 ──────────────────────

    private Workspace organization(String workspacePublicId, User actor, Set<WorkspaceRole> roles) {
        Workspace workspace = workspaceService.requireRole(workspacePublicId, actor.getId(), roles);
        if (workspace.isPersonal()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "개인 공간에는 멤버를 들일 수 없습니다.");
        }
        return workspace;
    }

    private void requireOwner(Workspace workspace, User actor) {
        if (workspaceService.roleOf(workspace.getId(), actor.getId()).orElse(null) != WorkspaceRole.OWNER) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "OWNER 를 바꾸는 일은 OWNER 만 할 수 있습니다.");
        }
    }

    private void requireAnotherOwner(Workspace workspace) {
        if (membershipRepository.countByWorkspaceIdAndRoleAndStatus(workspace.getId(), WorkspaceRole.OWNER,
                MembershipStatus.ACTIVE) <= 1) {
            throw new BusinessException(MemberErrorCode.LAST_OWNER);
        }
    }

    private WorkspaceMembership activeMembership(Workspace workspace, User target) {
        return membershipRepository.findByWorkspaceIdAndUserIdAndStatus(workspace.getId(), target.getId(), MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private User user(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
