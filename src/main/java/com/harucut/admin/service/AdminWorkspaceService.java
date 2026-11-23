package com.harucut.admin.service;

import com.harucut.admin.dto.AdminWorkspaceDtos;
import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceKind;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 플랫폼 관리자의 공간 종류(업체/주최자) 변경. ADMIN 확인은 URL 규칙·{@code AccountStateInterceptor} 가 한다 — 여기서는 감사 로그의 행위자만 찾는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AdminWorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    /** 조직 공간의 종류를 바꾼다. 개인 공간·없는 공간은 404. 같은 값이면 기록 없이 그대로 답한다 */
    public AdminWorkspaceDtos.KindResponse changeKind(String adminPublicId, String workspacePublicId, WorkspaceKind kind, String reason) {
        // 잠금 읽기가 이 트랜잭션의 첫 읽기다 — 브랜드 기본값 저장(ProjectService.patchWorkspaceDefaults)과 같은 공간 쓰기와 줄을 선다.
        // Workspace 는 전체 열을 쓰므로, 잠그지 않고 읽으면 그사이 커밋된 기본값을 낡은 값으로 되쓴다
        Workspace workspace = workspaceRepository.lockByPublicId(workspacePublicId)
                .filter(found -> !found.isPersonal())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        User actor = userRepository.findByPublicId(adminPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.FORBIDDEN));
        if (workspace.getKind() != kind) {
            workspace.changeKind(kind);
            auditLogService.record(actor.getId(), workspace.getId(), null, "WORKSPACE_KIND_CHANGED", "WORKSPACE",
                    workspace.getPublicId(), Map.of("kind", kind.name(), "reason", reason));
        }
        return new AdminWorkspaceDtos.KindResponse(workspace.getPublicId(), workspace.getKind());
    }
}
