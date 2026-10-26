package com.harucut.event.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventGrant;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 행사 사진 권한 판정의 한 곳 (ADR-0008 §2-4).
 *
 * <p>(a) 공간 OWNER·ADMIN 도 사진은 EventGrant 로만 본다 — 사용자 확인 대기. 결정이 바뀌면 {@link #photoPermissions} 만 고친다.
 */
@Component
@RequiredArgsConstructor
public class EventAccessPolicy {

    static final Set<WorkspaceRole> OPERATOR_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

    private final EventGrantRepository grantRepository;
    private final EventRepository eventRepository;
    private final WorkspaceService workspaceService;

    /** 이 행사를 운영(설정·게시·초대)할 수 있는가 */
    @Transactional(readOnly = true)
    public boolean isOperator(Event event, Long userId) {
        return workspaceService.roleOf(event.getWorkspaceId(), userId).filter(OPERATOR_ROLES::contains).isPresent();
    }

    /** 이 사용자가 이 행사에서 가진 사진 권한. 없으면 빈 집합 */
    @Transactional(readOnly = true)
    public Set<PhotoPermission> photoPermissions(Event event, Long userId) {
        return grantRepository.findByEventIdAndUserId(event.getId(), userId)
                .map(EventGrant::getPermissions)
                .map(EnumSet::copyOf)
                .orElseGet(() -> EnumSet.noneOf(PhotoPermission.class));
    }

    /** 행사가 있다는 것을 알려도 되는가 — 운영자이거나 사진 권한이 하나라도 있으면. 아니면 호출자는 404 */
    @Transactional(readOnly = true)
    public boolean canSee(Event event, Long userId) {
        return isOperator(event, userId) || grantRepository.findByEventIdAndUserId(event.getId(), userId).isPresent();
    }

    /** 운영 관문 — 없는 행사·운영자가 아니면 404(존재를 숨긴다) */
    @Transactional(readOnly = true)
    public Event requireOperable(Long userId, String eventPublicId) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (!isOperator(event, userId)) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        return event;
    }

    public Optional<EventGrant> grantOf(Event event, Long userId) {
        return grantRepository.findByEventIdAndUserId(event.getId(), userId);
    }
}
