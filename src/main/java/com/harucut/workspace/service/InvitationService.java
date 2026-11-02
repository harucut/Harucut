package com.harucut.workspace.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventGrant;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceInvitation;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.InvitationKind;
import com.harucut.workspace.enums.InvitationStatus;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceInvitationRepository;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 초대 미리보기·수락 — 수락 검사의 한 곳 (ADR-0008 §2-4).
 *
 * <p>(b) 이메일 일치 검사를 하지 않는다(사용자 확인 대기): 토큰을 가진 로그인 사용자 누구나 수락한다 — 링크 전달이 곧 초대다.
 * 바꾸게 되면 {@link #requireAcceptable} 만 고친다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InvitationService {

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EventGrantRepository grantRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MemberDtos.InvitationPreview preview(String actorPublicId, String token) {
        User actor = user(actorPublicId);
        WorkspaceInvitation invitation = requireAcceptable(token, actor);
        Workspace workspace = workspaceRepository.findById(invitation.getWorkspaceId()).orElseThrow();
        String eventName = invitation.getEventId() == null ? null
                : eventRepository.findById(invitation.getEventId()).map(Event::getName).orElse(null);
        String invitedBy = userRepository.findById(invitation.getInvitedByUserId()).map(User::getUsername).orElse(null);
        return new MemberDtos.InvitationPreview(invitation.getKind(), workspace.getName(), eventName, invitation.getRole(),
                invitation.getKind() == InvitationKind.EVENT ? invitation.getPermissions() : null, invitedBy,
                invitation.getExpiresAt().atZone(clock.getZone()).toOffsetDateTime());
    }

    public MemberDtos.InvitationAccepted accept(String actorPublicId, String token) {
        User actor = user(actorPublicId);
        WorkspaceInvitation invitation = requireAcceptable(token, actor);
        Workspace workspace = workspaceRepository.findById(invitation.getWorkspaceId()).orElseThrow();
        // 이미 같은 권한이면 토큰을 쓰지 않고 거절한다 — 같은 링크를 다른 사람에게 다시 건넬 수 있게
        if (invitation.getKind() == InvitationKind.WORKSPACE) {
            Optional<WorkspaceMembership> existing = membershipRepository.findByWorkspaceIdAndUserId(workspace.getId(), actor.getId());
            if (existing.filter(WorkspaceMembership::isActive).isPresent()) {
                throw new BusinessException(MemberErrorCode.ALREADY_MEMBER);
            }
            consume(invitation, actor);
            // consume 이 영속성 컨텍스트를 비워 위에서 읽은 행은 분리됐다 — 다시 읽어서 고친다
            membershipRepository.findByWorkspaceIdAndUserId(workspace.getId(), actor.getId())
                    .ifPresentOrElse(m -> m.rejoin(invitation.getRole()),
                            () -> membershipRepository.save(WorkspaceMembership.member(workspace.getId(), actor.getId(), invitation.getRole())));
            auditLogService.record(actor.getId(), workspace.getId(), null, "MEMBER_JOINED", "INVITATION",
                    invitation.getPublicId(), Map.of("role", invitation.getRole().name()));
            return new MemberDtos.InvitationAccepted(InvitationKind.WORKSPACE, workspace.getPublicId(), null,
                    invitation.getRole(), null);
        }

        Event event = eventRepository.findById(invitation.getEventId()).orElseThrow();
        Optional<EventGrant> existing = grantRepository.findByEventIdAndUserId(event.getId(), actor.getId());
        if (existing.filter(grant -> grant.getPermissions().containsAll(invitation.getPermissions())).isPresent()) {
            throw new BusinessException(MemberErrorCode.ALREADY_MEMBER);
        }
        consume(invitation, actor);
        EventGrant grant = grantRepository.findByEventIdAndUserId(event.getId(), actor.getId()).orElseGet(() -> grantRepository.save(
                new EventGrant(event.getId(), actor.getId(), invitation.getPermissions(), invitation.getInvitedByUserId())));
        grant.merge(invitation.getPermissions());
        auditLogService.record(actor.getId(), workspace.getId(), event.getId(), "CUSTOMER_JOINED", "INVITATION",
                invitation.getPublicId(), null);
        return new MemberDtos.InvitationAccepted(InvitationKind.EVENT, null, event.getPublicId(), null, grant.getPermissions());
    }

    // 수락할 수 있는 초대인가. 없거나 이미 쓴·회수된 것은 MEMBER-002, 기한이 지난 것은 MEMBER-001
    private WorkspaceInvitation requireAcceptable(String token, User actor) {
        WorkspaceInvitation invitation = invitationRepository.findByTokenHash(OpaqueTokens.hash(token.strip()))
                .filter(found -> found.getStatus() == InvitationStatus.OPEN)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.INVITATION_INVALID));
        if (invitation.expiredAt(LocalDateTime.now(clock))) {
            throw new BusinessException(MemberErrorCode.INVITATION_EXPIRED);
        }
        return invitation;
    }

    private void consume(WorkspaceInvitation invitation, User actor) {
        if (invitationRepository.consume(invitation.getId()) != 1) {
            throw new BusinessException(MemberErrorCode.INVITATION_INVALID);   // 동시에 다른 사람이 먼저 썼다
        }
        // consume 이 영속성 컨텍스트를 비웠다 — 다시 읽어 수락자를 적는다
        invitationRepository.findById(invitation.getId()).ifPresent(fresh -> fresh.accepted(actor.getId(), LocalDateTime.now(clock)));
    }

    private User user(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }
}
