package com.harucut.event.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventGrant;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.project.entity.ProjectMembership;
import com.harucut.project.repository.ProjectMembershipRepository;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 행사 권한 판정의 한 곳 (ADR-0008 §2-4, ADR-0009 §3).
 *
 * <p>운영 권한: 공간 OWNER·ADMIN 은 셋 다. OPERATOR 는 행사 권한(EventGrant.operations) ∪ 그 행사 프로젝트의 멤버 권한 —
 * 공간 역할이 OPERATOR 일 때만 효력이 있다(역할을 내리면 운영 권한도 사라진다).
 * <p>사진 권한: EventGrant ∪ 프로젝트 멤버 권한. (a) 공간 OWNER·ADMIN 도 사진은 이 둘로만 본다 — 결정이 바뀌면 여기만 고친다.
 */
@Component
@RequiredArgsConstructor
public class EventAccessPolicy {

    static final Set<WorkspaceRole> OPERATOR_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

    private final EventGrantRepository grantRepository;
    private final EventRepository eventRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final WorkspaceService workspaceService;

    public enum Source { EVENT, PROJECT, BOTH }

    /**
     * 한 사람이 한 행사에서 가진 것. role 은 공간 멤버일 때만.
     * sources 는 OPERATOR 일 때만 — 권한마다 행사에서(EVENT)·프로젝트에서(PROJECT)·둘 다(BOTH). PROJECT·BOTH 는 행사 화면에서 뺄 수 없다
     */
    public record Access(WorkspaceRole role, Set<OperationPermission> operations, Set<PhotoPermission> permissions,
                         Map<String, Source> sources) {

        public boolean admin() {
            return role != null && OPERATOR_ROLES.contains(role);
        }

        public boolean canSee() {
            return admin() || !operations.isEmpty() || !permissions.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Access accessOf(Event event, Long userId) {
        WorkspaceRole role = workspaceService.roleOf(event.getWorkspaceId(), userId).orElse(null);
        Optional<EventGrant> grant = grantRepository.findByEventIdAndUserId(event.getId(), userId);
        Optional<ProjectMembership> member = event.getProjectId() == null ? Optional.empty()
                : projectMembershipRepository.findByProjectIdAndUserId(event.getProjectId(), userId);

        Set<PhotoPermission> eventPhotos = grant.map(EventGrant::getPermissions).orElse(Set.of());
        Set<PhotoPermission> projectPhotos = role == WorkspaceRole.OPERATOR
                ? member.map(ProjectMembership::getPermissions).orElse(Set.of()) : Set.of();
        Set<PhotoPermission> permissions = union(PhotoPermission.class, eventPhotos, projectPhotos);

        if (role != null && OPERATOR_ROLES.contains(role)) {
            return new Access(role, OperationPermission.all(), permissions, Map.of());
        }
        if (role != WorkspaceRole.OPERATOR) {
            return new Access(role, EnumSet.noneOf(OperationPermission.class), permissions, Map.of());
        }
        Set<OperationPermission> eventOps = grant.map(EventGrant::getOperations).orElse(Set.of());
        Set<OperationPermission> projectOps = member.map(ProjectMembership::getOperations).orElse(Set.of());
        Map<String, Source> sources = new LinkedHashMap<>();
        for (OperationPermission op : OperationPermission.values()) {
            source(sources, op.name(), eventOps.contains(op), projectOps.contains(op));
        }
        for (PhotoPermission photo : PhotoPermission.values()) {
            source(sources, photo.name(), eventPhotos.contains(photo), projectPhotos.contains(photo));
        }
        return new Access(role, union(OperationPermission.class, eventOps, projectOps), permissions, sources);
    }

    /** 이 사용자가 이 행사에서 가진 사진 권한. 없으면 빈 집합 */
    @Transactional(readOnly = true)
    public Set<PhotoPermission> photoPermissions(Event event, Long userId) {
        return accessOf(event, userId).permissions();
    }

    /** 행사가 있다는 것을 알려도 되는가 — 운영할 수 있거나 사진 권한이 하나라도 있으면. 아니면 호출자는 404 */
    @Transactional(readOnly = true)
    public boolean canSee(Event event, Long userId) {
        return accessOf(event, userId).canSee();
    }

    /** 운영 관문 — 볼 수 없으면 404(존재를 숨긴다), 볼 수는 있지만 이 운영 권한이 없으면 403 GEN-021 */
    @Transactional(readOnly = true)
    public Event requireOperation(Long userId, String eventPublicId, OperationPermission operation) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        requireOperation(event, userId, operation);
        return event;
    }

    @Transactional(readOnly = true)
    public Access requireOperation(Event event, Long userId, OperationPermission operation) {
        Access access = accessOf(event, userId);
        if (!access.canSee()) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        if (operation != null && !access.operations().contains(operation)) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "이 행사에 " + operation + " 권한이 없습니다.");
        }
        if (operation == null && access.operations().isEmpty()) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "이 행사를 운영할 권한이 없습니다.");
        }
        return access;
    }

    /**
     * 사진 관문 — 행사를 볼 수 없으면 404(존재를 숨긴다), 볼 수는 있지만 이 권한이 없으면 403 PHOTO-001.
     */
    @Transactional(readOnly = true)
    public Event requirePhoto(Long userId, String eventPublicId, PhotoPermission permission) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Access access = accessOf(event, userId);
        if (!access.canSee()) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        if (!access.permissions().contains(permission)) {
            throw new BusinessException(PhotoErrorCode.PERMISSION_REQUIRED);
        }
        return event;
    }

    public Optional<EventGrant> grantOf(Event event, Long userId) {
        return grantRepository.findByEventIdAndUserId(event.getId(), userId);
    }

    private static void source(Map<String, Source> sources, String name, boolean fromEvent, boolean fromProject) {
        if (fromEvent || fromProject) {
            sources.put(name, fromEvent && fromProject ? Source.BOTH : fromEvent ? Source.EVENT : Source.PROJECT);
        }
    }

    @SafeVarargs
    private static <E extends Enum<E>> Set<E> union(Class<E> type, Set<E>... sets) {
        EnumSet<E> all = EnumSet.noneOf(type);
        for (Set<E> set : sets) {
            all.addAll(set);
        }
        return all;
    }
}
