package com.harucut.event.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventGrant;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceInvitation;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceInvitationRepository;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 행사 사진 권한(EventGrant)과 행사 고객 초대 (ADR-0008 §2-2). 운영 관문은 {@link EventAccessPolicy} 한 곳이다.
 * EventService 에 의존하지 않는다 — EventService 가 행사를 만들 때 이쪽을 부르기 때문(순환 방지).
 */
@Service
@Transactional
public class EventGrantService {

    private final EventAccessPolicy policy;
    private final EventGrantRepository grantRepository;
    private final EventRepository eventRepository;
    private final com.harucut.media.repository.ComposeJobRepository composeJobRepository;
    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final Duration invitationTtl;

    public EventGrantService(EventAccessPolicy policy, EventGrantRepository grantRepository, EventRepository eventRepository,
                             com.harucut.media.repository.ComposeJobRepository composeJobRepository,
                             WorkspaceInvitationRepository invitationRepository,
                             WorkspaceMembershipRepository membershipRepository, WorkspaceRepository workspaceRepository,
                             UserRepository userRepository,
                             AuditLogService auditLogService, Clock clock,
                             @Value("${workspace.invitation-ttl:7d}") Duration invitationTtl) {
        this.policy = policy;
        this.grantRepository = grantRepository;
        this.eventRepository = eventRepository;
        this.composeJobRepository = composeJobRepository;
        this.invitationRepository = invitationRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.invitationTtl = invitationTtl;
    }

    /** 행사를 만든 사람에게 네 권한 — 단일 행사 주최자가 바로 사진을 보도록 */
    public void grantCreator(Long eventId, Long userId) {
        grantRepository.save(new EventGrant(eventId, userId, PhotoPermission.all(), null));
    }

    public MemberDtos.InvitationIssued inviteCustomer(String actorPublicId, String eventPublicId,
                                                      MemberDtos.EventInvitationRequest request) {
        User actor = user(actorPublicId);
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        boolean includePast = Boolean.TRUE.equals(request.includePast());
        requireWithinOwn(policy.requireOperation(event, actor.getId(), OperationPermission.INVITE_CUSTOMERS), request.permissions(), includePast);
        String token = OpaqueTokens.generate();
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(invitationTtl);
        WorkspaceInvitation invitation = invitationRepository.save(WorkspaceInvitation.eventCustomer(event.getWorkspaceId(),
                event.getId(), request.permissions(), includePast, blankToNull(request.note()), OpaqueTokens.hash(token), expiresAt,
                actor.getId()));
        auditLogService.record(actor.getId(), event.getWorkspaceId(), event.getId(), "CUSTOMER_INVITED", "INVITATION",
                invitation.getPublicId(), Map.of("permissions", sorted(request.permissions()), "includePast", includePast));
        return new MemberDtos.InvitationIssued(invitation.getPublicId(), token, offset(expiresAt));
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.EventGrantView> grants(String actorPublicId, String eventPublicId) {
        User actor = user(actorPublicId);
        Event event = policy.requireOperation(actor.getId(), eventPublicId, OperationPermission.INVITE_CUSTOMERS);
        List<EventGrant> grants = grantRepository.findAllByEventIdOrderByIdAsc(event.getId());
        Map<Long, User> users = userRepository.findAllById(grants.stream().map(EventGrant::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return grants.stream().filter(grant -> users.containsKey(grant.getUserId()))
                .map(grant -> view(grant, users.get(grant.getUserId())))
                .toList();
    }

    public MemberDtos.EventGrantView update(String actorPublicId, String eventPublicId, Long grantId,
                                            Set<PhotoPermission> permissions, boolean includePast) {
        User actor = user(actorPublicId);
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        EventAccessPolicy.Access access = policy.requireOperation(event, actor.getId(), OperationPermission.INVITE_CUSTOMERS);
        EventGrant grant = grantOf(event, grantId);
        requireCustomerGrant(access, event, grant);
        requireWithinOwn(access, permissions, includePast);
        LocalDateTime now = LocalDateTime.now(clock);
        // 권한을 더하면 대상의 범위까지 함께 넘기는 셈이다 — 자기 범위를 넘으면 더하지 못한다. 빼는 것은 된다
        if (!grant.getPermissions().containsAll(permissions) && !access.canShare(grant.photosFromAfter(includePast, now))) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "자기 사진 범위 밖까지 권한을 넓힐 수 없습니다.");
        }
        grant.replace(permissions, includePast, now);
        User target = userRepository.findById(grant.getUserId()).orElseThrow();
        auditLogService.record(actor.getId(), event.getWorkspaceId(), event.getId(), "GRANT_CHANGED", "USER",
                target.getPublicId(), Map.of("permissions", sorted(permissions), "includePast", includePast));
        return view(grant, target);
    }

    public void delete(String actorPublicId, String eventPublicId, Long grantId) {
        User actor = user(actorPublicId);
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        EventAccessPolicy.Access access = policy.requireOperation(event, actor.getId(), OperationPermission.INVITE_CUSTOMERS);
        EventGrant grant = grantOf(event, grantId);
        requireCustomerGrant(access, event, grant);
        grantRepository.delete(grant);
        String target = userRepository.findById(grant.getUserId()).map(User::getPublicId).orElse(null);
        auditLogService.record(actor.getId(), event.getWorkspaceId(), event.getId(), "GRANT_REVOKED", "USER", target, null);
    }

    /**
     * 행사 운영자 배정(ADR-0009 §3) — 공간 OWNER·ADMIN 만, 대상은 그 공간의 OPERATOR 멤버. 빈 목록이면 운영 권한을 뺀다
     * (사진 권한도 없으면 행사 권한 행을 지운다). 사진 권한은 고객 초대·권한 수정으로 따로 준다
     */
    public MemberDtos.EventGrantView assignOperator(String actorPublicId, String eventPublicId, String targetPublicId,
                                                    Set<OperationPermission> operations) {
        User actor = user(actorPublicId);
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (!policy.requireOperation(event, actor.getId(), null).admin()) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "운영자 배정은 공간 OWNER·ADMIN 만 할 수 있습니다.");
        }
        User target = user(targetPublicId);
        if (membershipRepository.findByWorkspaceIdAndUserIdAndStatus(event.getWorkspaceId(), target.getId(), MembershipStatus.ACTIVE)
                .filter(m -> m.getRole() == WorkspaceRole.OPERATOR).isEmpty()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 공간의 OPERATOR 멤버에게만 운영 권한을 줄 수 있습니다.");
        }
        EventGrant grant = grantRepository.findByEventIdAndUserId(event.getId(), target.getId())
                .orElseGet(() -> new EventGrant(event.getId(), target.getId(), Set.of(), actor.getId()));
        grant.replaceOperations(operations);
        if (grant.isEmpty()) {
            if (grant.getId() != null) {
                grantRepository.delete(grant);
            }
        } else {
            grant = grantRepository.save(grant);
        }
        auditLogService.record(actor.getId(), event.getWorkspaceId(), event.getId(), "OPERATOR_ASSIGNED", "USER",
                target.getPublicId(), Map.of("operations", operations.stream().map(Enum::name).sorted().toList()));
        return view(grant, target);
    }

    /**
     * 내가 접근할 수 있는 행사 — 운영하는 공간(OWNER·ADMIN)의 행사, 테마를 고칠 수 있는 공간(DESIGNER)의 행사,
     * OPERATOR 로 운영 권한(행사·프로젝트)이 있는 행사, 사진 권한(행사·프로젝트)이 있는 행사. 최신순. access 는 EventAccessPolicy 와 같은 판정이다
     */
    @Transactional(readOnly = true)
    public List<MemberDtos.MyEvent> myEvents(String actorPublicId) {
        User actor = user(actorPublicId);
        Map<Long, WorkspaceRole> roles = membershipRepository
                .findAllByUserIdAndStatus(actor.getId(), MembershipStatus.ACTIVE).stream()
                .collect(Collectors.toMap(WorkspaceMembership::getWorkspaceId, WorkspaceMembership::getRole));

        Map<Long, Event> candidates = new LinkedHashMap<>();
        roles.forEach((workspaceId, role) -> {
            if (EventAccessPolicy.OPERATOR_ROLES.contains(role) || role == WorkspaceRole.OPERATOR || role == WorkspaceRole.DESIGNER) {
                eventRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).forEach(e -> candidates.put(e.getId(), e));
            }
        });
        eventRepository.findAllById(grantRepository.findAllByUserId(actor.getId()).stream().map(EventGrant::getEventId).toList())
                .forEach(e -> candidates.putIfAbsent(e.getId(), e));
        Map<Long, Long> completed = candidates.isEmpty() ? Map.of()
                : composeJobRepository.countCompletedByEventIds(candidates.keySet(), LocalDateTime.now(clock)).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        Map<Long, String> workspaceIds = workspaceRepository.findAllById(candidates.values().stream().map(Event::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Workspace::getPublicId));
        return candidates.values().stream()
                .sorted(Comparator.comparing(Event::getCreatedAt).reversed())
                .map(e -> Map.entry(e, policy.accessOf(e, actor.getId())))
                .filter(entry -> entry.getValue().canSee())
                .map(entry -> {
                    EventAccessPolicy.Access access = entry.getValue();
                    Map<String, String> sources = new LinkedHashMap<>();
                    access.sources().forEach((name, source) -> sources.put(name, source.name()));
                    return new MemberDtos.MyEvent(entry.getKey().getPublicId(), workspaceIds.get(entry.getKey().getWorkspaceId()),
                            entry.getKey().getName(), entry.getKey().getStatus(),
                            new MemberDtos.MyEvent.Access(access.role(), access.permissions(), access.operations(),
                                    sources.isEmpty() ? null : sources, offset(access.photosFrom())),
                            access.themeOnly() ? null : access.photosFrom() == null
                                    ? completed.getOrDefault(entry.getKey().getId(), 0L)
                                    // 범위 밖 사진 수는 알리지 않는다 — 빈 목록과 어긋나지 않게
                                    : composeJobRepository.countCompletedSince(entry.getKey().getId(), access.photosFrom(), LocalDateTime.now(clock)));
                })
                .toList();
    }

    // OPERATOR 는 자기가 가진 사진 권한·범위 안에서만 고객에게 준다(OWNER·ADMIN 은 제한 없음)
    private static void requireWithinOwn(EventAccessPolicy.Access access, Set<PhotoPermission> permissions, boolean includePast) {
        if (!access.admin() && (!access.permissions().containsAll(permissions) || (includePast && access.photosFrom() != null))) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "자기가 가진 사진 권한 안에서만 줄 수 있습니다.");
        }
    }

    // OPERATOR 는 고객의 행만 바꾸거나 지운다 — 대상이 이 행사를 운영할 수 있으면(행사·프로젝트에서 받은 운영 권한, 공간 OWNER·ADMIN)
    // 그 사람의 사진 권한 행은 OWNER·ADMIN 만 다룬다. 행의 운영 칸만 보면 프로젝트로만 운영 권한을 받은 운영자를 고객으로 착각한다
    private void requireCustomerGrant(EventAccessPolicy.Access access, Event event, EventGrant grant) {
        if (!access.admin() && !policy.accessOf(event, grant.getUserId()).operations().isEmpty()) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "운영자의 권한은 공간 OWNER·ADMIN 만 바꿀 수 있습니다.");
        }
    }

    private EventGrant grantOf(Event event, Long grantId) {
        return grantRepository.findById(grantId)
                .filter(grant -> grant.getEventId().equals(event.getId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private MemberDtos.EventGrantView view(EventGrant grant, User user) {
        return new MemberDtos.EventGrantView(grant.getId(), user.getPublicId(), user.getUsername(), grant.getPermissions(),
                grant.getOperations(), offset(grant.getCreatedAt()), offset(grant.getPhotosFrom()));
    }

    private User user(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private static List<String> sorted(Set<PhotoPermission> permissions) {
        return permissions.stream().map(Enum::name).sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
