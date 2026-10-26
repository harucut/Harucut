package com.harucut.project.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.service.EffectiveSettingsResolver;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.entity.Project;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트와 브랜드 기본값 (ADR-0009 §2, P3-1). 조직 공간의 OWNER·ADMIN 만.
 * 기본값을 바꿔도 이미 게시한 행사는 바뀌지 않는다 — 다시 게시해야 새 값이 들어간다(게시 때 발행본에 굳는다).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ProjectService {

    private static final Set<WorkspaceRole> OPERATOR_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final EffectiveSettingsResolver settingsResolver;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectDtos.Project create(String userPublicId, String workspacePublicId, ProjectDtos.CreateRequest request) {
        User user = user(userPublicId);
        Workspace workspace = organization(workspacePublicId, user);
        String defaults = request.brandDefaults() == null ? null
                : settingsResolver.merge(null, objectMapper.valueToTree(request.brandDefaults()), true);
        Project project = projectRepository.save(new Project(workspace.getId(), name(request.name()), defaults));
        auditLogService.record(user.getId(), workspace.getId(), null, "PROJECT_CREATED", "PROJECT", project.getPublicId(), null);
        return view(project, workspace);
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.Project> list(String userPublicId, String workspacePublicId) {
        Workspace workspace = organization(workspacePublicId, user(userPublicId));
        return projectRepository.findAllByWorkspaceIdOrderByIdDesc(workspace.getId()).stream()
                .map(project -> view(project, workspace)).toList();
    }

    /** body: name·brandDefaults(머지 패치 — 키마다 null 이면 지움, 테마는 객체째)·archived(true 보관, false 되살림) 중 보낸 것만 */
    public ProjectDtos.Project patch(String userPublicId, String projectPublicId, JsonNode body) {
        User user = user(userPublicId);
        Project project = projectRepository.findByPublicId(projectPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Workspace workspace = workspaceService.requireRole(project.getWorkspaceId(), user.getId(), OPERATOR_ROLES);
        if (body.has("name")) {
            project.rename(name(body.get("name").isNull() ? null : body.get("name").asString()));
        }
        if (body.has("brandDefaults")) {
            JsonNode patch = body.get("brandDefaults");
            if (patch.isNull()) {
                project.changeBrandDefaults(null);
            } else if (patch.isObject()) {
                project.changeBrandDefaults(settingsResolver.merge(project.getBrandDefaults(), patch, true));
            } else {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "brandDefaults 는 객체여야 합니다.");
            }
        }
        if (body.has("archived")) {
            if (body.get("archived").asBoolean(false)) {
                project.archive(LocalDateTime.now(clock));
            } else {
                project.unarchive();
            }
        }
        auditLogService.record(user.getId(), workspace.getId(), null, "PROJECT_CHANGED", "PROJECT", project.getPublicId(),
                Map.of("fields", body.propertyNames().stream().sorted().toList()));
        return view(projectRepository.saveAndFlush(project), workspace);
    }

    @Transactional(readOnly = true)
    public ProjectDtos.WorkspaceDefaults workspaceDefaults(String userPublicId, String workspacePublicId) {
        Workspace workspace = organization(workspacePublicId, user(userPublicId));
        return new ProjectDtos.WorkspaceDefaults(workspace.getPublicId(), settingsResolver.read(workspace.getBrandDefaults()));
    }

    /** body 가 곧 머지 패치 — 상속 가능한 필드만, null 이면 지움 */
    public ProjectDtos.WorkspaceDefaults patchWorkspaceDefaults(String userPublicId, String workspacePublicId, JsonNode body) {
        User user = user(userPublicId);
        Workspace workspace = organization(workspacePublicId, user);
        if (!body.isObject()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "기본값은 객체여야 합니다.");
        }
        workspace.changeBrandDefaults(settingsResolver.merge(workspace.getBrandDefaults(), body, true));
        auditLogService.record(user.getId(), workspace.getId(), null, "WORKSPACE_BRAND_DEFAULTS_CHANGED", "WORKSPACE",
                workspace.getPublicId(), Map.of("fields", body.propertyNames().stream().sorted().toList()));
        return new ProjectDtos.WorkspaceDefaults(workspace.getPublicId(), settingsResolver.read(workspace.getBrandDefaults()));
    }

    private Workspace organization(String workspacePublicId, User user) {
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), OPERATOR_ROLES);
        if (workspace.isPersonal()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "프로젝트·기본값은 조직 공간에만 둘 수 있습니다.");
        }
        return workspace;
    }

    private ProjectDtos.Project view(Project project, Workspace workspace) {
        return new ProjectDtos.Project(project.getPublicId(), workspace.getPublicId(), project.getName(),
                settingsResolver.read(project.getBrandDefaults()), project.isArchived(), offset(project.getCreatedAt()));
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private User user(String publicId) {
        return userRepository.findByPublicId(publicId).orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 100) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "name 은 1~100자여야 합니다.");
        }
        return value.strip();
    }
}
