package com.harucut.project.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.service.EffectiveSettingsResolver;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.entity.Project;
import com.harucut.project.entity.ProjectMembership;
import com.harucut.project.repository.ProjectMembershipRepository;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 프로젝트와 브랜드 기본값 (ADR-0009 §2, P3-1). 조직 공간의 OWNER·ADMIN 만.
 * 기본값을 바꿔도 이미 게시한 행사는 바뀌지 않는다 — 다시 게시해야 새 값이 들어간다(게시 때 발행본에 굳는다).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ProjectService {

    private static final Set<WorkspaceRole> OPERATOR_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
    // 프로젝트 목록 — OPERATOR 는 멤버인 프로젝트만 본다
    private static final Set<WorkspaceRole> LIST_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.OPERATOR);

    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EntityManager entityManager;
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
        User user = user(userPublicId);
        Workspace workspace = organization(workspacePublicId, user, LIST_ROLES);
        boolean admin = workspaceService.roleOf(workspace.getId(), user.getId()).filter(OPERATOR_ROLES::contains).isPresent();
        return projectRepository.findAllByWorkspaceIdOrderByIdDesc(workspace.getId()).stream()
                .filter(project -> admin || membershipRepository.findByProjectIdAndUserId(project.getId(), user.getId()).isPresent())
                .map(project -> view(project, workspace)).toList();
    }

    // ── 프로젝트 멤버(ADR-0009 §3) — 공간 OWNER·ADMIN 이 OPERATOR 멤버에게 준다 ──

    @Transactional(readOnly = true)
    public List<ProjectDtos.Member> members(String userPublicId, String projectPublicId) {
        Project project = adminProject(user(userPublicId), projectPublicId);
        List<ProjectMembership> members = membershipRepository.findAllByProjectIdOrderByIdAsc(project.getId());
        Map<Long, User> users = userRepository.findAllById(members.stream().map(ProjectMembership::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return members.stream().filter(m -> users.containsKey(m.getUserId()))
                .map(m -> memberView(m, users.get(m.getUserId()))).toList();
    }

    /** 권한을 통째로 바꾼다(없으면 만든다). 둘 다 비면 멤버에서 뺀다. 대상은 그 공간의 OPERATOR 멤버 */
    public ProjectDtos.Member putMember(String userPublicId, String projectPublicId, String targetPublicId,
                                        ProjectDtos.MemberRequest request) {
        User actor = user(userPublicId);
        Project project = adminProject(actor, projectPublicId);
        User target = user(targetPublicId);
        if (workspaceMembershipRepository.findByWorkspaceIdAndUserIdAndStatus(project.getWorkspaceId(), target.getId(), MembershipStatus.ACTIVE)
                .filter(m -> m.getRole() == WorkspaceRole.OPERATOR).isEmpty()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 공간의 OPERATOR 멤버만 프로젝트 멤버가 될 수 있습니다.");
        }
        ProjectMembership member = membershipRepository.findByProjectIdAndUserId(project.getId(), target.getId())
                .orElseGet(() -> new ProjectMembership(project.getId(), target.getId(), actor.getId()));
        member.replace(request.operations(), request.permissions());
        if (request.operations().isEmpty() && request.permissions().isEmpty()) {
            if (member.getId() != null) {
                membershipRepository.delete(member);
            }
        } else {
            member = membershipRepository.save(member);
        }
        auditLogService.record(actor.getId(), project.getWorkspaceId(), null, "PROJECT_MEMBER_CHANGED", "USER", target.getPublicId(),
                Map.of("project", project.getPublicId(),
                        "operations", request.operations().stream().map(Enum::name).sorted().toList(),
                        "permissions", request.permissions().stream().map(Enum::name).sorted().toList()));
        return memberView(member, target);
    }

    public void deleteMember(String userPublicId, String projectPublicId, String targetPublicId) {
        User actor = user(userPublicId);
        Project project = adminProject(actor, projectPublicId);
        User target = user(targetPublicId);
        ProjectMembership member = membershipRepository.findByProjectIdAndUserId(project.getId(), target.getId())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        membershipRepository.delete(member);
        auditLogService.record(actor.getId(), project.getWorkspaceId(), null, "PROJECT_MEMBER_REMOVED", "USER", target.getPublicId(),
                Map.of("project", project.getPublicId()));
    }

    private Project adminProject(User user, String projectPublicId) {
        Project project = projectRepository.findByPublicId(projectPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        workspaceService.requireRole(project.getWorkspaceId(), user.getId(), OPERATOR_ROLES);
        return project;
    }

    private ProjectDtos.Member memberView(ProjectMembership member, User user) {
        return new ProjectDtos.Member(user.getPublicId(), user.getUsername(), member.getOperations(), member.getPermissions(),
                offset(member.getCreatedAt()));
    }

    /** body: name·brandDefaults(머지 패치 — 키마다 null 이면 지움, 테마는 객체째)·archived(true 보관, false 되살림) 중 보낸 것만 */
    public ProjectDtos.Project patch(String userPublicId, String projectPublicId, JsonNode body) {
        User user = user(userPublicId);
        Project project = projectRepository.findByPublicId(projectPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        // 기본값은 읽고 머지해 다시 쓴다 — 두 관리자의 동시 PATCH 가 서로를 지우지 않게 줄 세운다. 순서는 「공간 → 프로젝트」(#32 잠금 표).
        // 프로젝트는 이미 읽었으므로 잠금 읽기로 다시 읽는다(refresh) — 잠금을 기다리는 동안 커밋된 변경을 본다
        workspaceRepository.lockById(project.getWorkspaceId());
        entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
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
        // 읽고 머지해 다시 쓴다 — 공간 행을 잠금 읽기로 처음 읽어 동시 PATCH 를 줄 세운다(나중 쓰기가 앞 변경을 지우지 않게)
        workspaceRepository.lockByPublicId(workspacePublicId);
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
        return organization(workspacePublicId, user, OPERATOR_ROLES);
    }

    private Workspace organization(String workspacePublicId, User user, Set<WorkspaceRole> roles) {
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), roles);
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
