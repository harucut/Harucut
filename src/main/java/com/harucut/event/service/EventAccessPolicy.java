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

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 행사 권한 판정의 한 곳 (ADR-0008 §2-4, ADR-0009 §3).
 *
 * <p>운영 권한: 공간 OWNER·ADMIN 은 전부. DESIGNER 는 그 공간 행사에 EDIT_THEME 만(게시·기기·사진은 자동으로 주지 않는다, R5 L368). OPERATOR 는 행사 권한(EventGrant.operations) ∪ 그 행사 프로젝트의 멤버 권한 —
 * 공간 역할이 OPERATOR 일 때만 효력이 있다(역할을 내리면 운영 권한도 사라진다).
 * <p>사진 권한: EventGrant ∪ 프로젝트 멤버 권한(공간 역할이 OWNER·ADMIN·OPERATOR 일 때 — OPERATOR 를 ADMIN 으로 올려도 남고,
 * VIEWER·DESIGNER 로 내리거나 멤버가 아니면 프로젝트 쪽은 빠진다). (a) 공간 OWNER·ADMIN 도 사진은 이 둘로만 본다 — 결정이 바뀌면 여기만 고친다.
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
     * sources 는 OPERATOR 일 때만 — 권한마다 행사에서(EVENT)·프로젝트에서(PROJECT)·둘 다(BOTH). PROJECT·BOTH 는 행사 화면에서 뺄 수 없다.
     * photosFrom 은 이 시각 이후 사진만 볼 수 있다는 뜻이다(R5 L347). NULL 이면 제한 없음. 프로젝트 멤버(내부 담당자) 사진 권한이 있으면 제한이 없다
     */
    public record Access(WorkspaceRole role, Set<OperationPermission> operations, Set<PhotoPermission> permissions,
                         Map<String, Source> sources, LocalDateTime photosFrom) {

        public boolean admin() {
            return role != null && OPERATOR_ROLES.contains(role);
        }

        public boolean canSee() {
            return admin() || !operations.isEmpty() || !permissions.isEmpty();
        }

        /** 테마만 고칠 수 있는 사람 — 사진 수·사용량을 주지 않는다 */
        public boolean themeOnly() {
            return permissions.isEmpty() && operations.equals(EnumSet.of(OperationPermission.EDIT_THEME));
        }

        /** 범위가 제한됐고 테마 말고는 운영 권한이 없는 사람 — 사용량으로 숨긴 사진 수를 짐작하지 못하게 한다 */
        public boolean limitedRecipient() {
            return photosFrom != null && operations.stream().allMatch(operation -> operation == OperationPermission.EDIT_THEME);
        }

        /**
         * 이 범위의 사진 권한을 남에게 넘길 수 있는가 — OWNER·ADMIN 은 늘, 아니면 자기 범위 안에서만.
         * 범위는 권한마다가 아니라 행마다라, 넓은 범위의 행에 권한을 더하면 그 범위까지 함께 넘기는 셈이다
         */
        public boolean canShare(LocalDateTime scope) {
            if (admin()) {
                return true;
            }
            if (scope == null) {
                return photosFrom == null && !permissions.isEmpty();
            }
            return photosFrom == null || !scope.isBefore(photosFrom);
        }
    }

    @Transactional(readOnly = true)
    public Access accessOf(Event event, Long userId) {
        WorkspaceRole role = workspaceService.roleOf(event.getWorkspaceId(), userId).orElse(null);
        Optional<EventGrant> grant = grantRepository.findByEventIdAndUserId(event.getId(), userId);
        Optional<ProjectMembership> member = event.getProjectId() == null ? Optional.empty()
                : projectMembershipRepository.findByProjectIdAndUserId(event.getProjectId(), userId);

        Set<PhotoPermission> eventPhotos = grant.map(EventGrant::getPermissions).orElse(Set.of());
        boolean managing = role == WorkspaceRole.OPERATOR || (role != null && OPERATOR_ROLES.contains(role));
        Set<PhotoPermission> projectPhotos = managing
                ? member.map(ProjectMembership::getPermissions).orElse(Set.of()) : Set.of();
        Set<PhotoPermission> permissions = union(PhotoPermission.class, eventPhotos, projectPhotos);
        LocalDateTime photosFrom = projectPhotos.isEmpty() ? grant.map(EventGrant::getPhotosFrom).orElse(null) : null;

        if (role != null && OPERATOR_ROLES.contains(role)) {
            return new Access(role, OperationPermission.all(), permissions, Map.of(), photosFrom);
        }
        if (role == WorkspaceRole.DESIGNER) {
            return new Access(role, EnumSet.of(OperationPermission.EDIT_THEME), permissions, Map.of(), photosFrom);
        }
        if (role != WorkspaceRole.OPERATOR) {
            return new Access(role, EnumSet.noneOf(OperationPermission.class), permissions, Map.of(), photosFrom);
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
        return new Access(role, union(OperationPermission.class, eventOps, projectOps), permissions, sources, photosFrom);
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
        return requirePhotoScope(userId, eventPublicId, permission).event();
    }

    /** 사진 관문 + 이 사람이 볼 수 있는 사진 범위 — 범위 밖 사진은 없는 것처럼 다룬다 */
    @Transactional(readOnly = true)
    public PhotoScope requirePhotoScope(Long userId, String eventPublicId, PhotoPermission permission) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Access access = accessOf(event, userId);
        if (!access.canSee()) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        if (!access.permissions().contains(permission)) {
            throw new BusinessException(PhotoErrorCode.PERMISSION_REQUIRED);
        }
        return new PhotoScope(event, access.photosFrom());
    }

    /** 한 행사에서 볼 수 있는 사진 범위. photosFrom 이 NULL 이면 제한 없음 */
    public record PhotoScope(Event event, LocalDateTime photosFrom) {

        public boolean covers(LocalDateTime createdAt) {
            return photosFrom == null || !createdAt.isBefore(photosFrom);
        }

        /** 목록 쿼리의 시작 시각 — 요청한 값과 범위 중 늦은 쪽 */
        public LocalDateTime from(LocalDateTime requested) {
            return photosFrom == null || (requested != null && requested.isAfter(photosFrom)) ? requested : photosFrom;
        }
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
