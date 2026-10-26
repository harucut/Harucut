package com.harucut.export.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventAccessPolicy;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.entity.ExportJob;
import com.harucut.export.entity.ExportJobItem;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.exception.ExportErrorCode;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.media.entity.ComposeJob;
import com.harucut.project.entity.Project;
import com.harucut.project.repository.ProjectMembershipRepository;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여러 행사 묶음 다운로드 (ADR-0009 §6, P3-5) — 프로젝트·공간 범위. 행사 묶음(ExportService)과 같은 흐름·모양이고 범위만 다르다:
 * 요청에 담을 행사를 명시하고, 권한(BULK_DOWNLOAD 사진 권한·이용권 기능)은 행사마다 본다 — 없는 행사가 있으면 조용히 빼지 않고 403.
 * 압축·만료·무효화·수령 재검사는 행사 묶음과 같은 작업자·규칙을 쓴다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class MultiExportService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<WorkspaceRole> ADMIN_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
    private static final Set<WorkspaceRole> PROJECT_ROLES = EnumSet.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.OPERATOR);

    private final ExportService exportService;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobItemRepository itemRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final WorkspaceService workspaceService;
    private final EventAccessPolicy policy;
    private final EntitlementService entitlementService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // 묶음의 범위 — 공간(필수)과 프로젝트(PROJECT 일 때)
    private record Scope(ExportScope kind, Workspace workspace, Project project) {

        Long projectId() {
            return project == null ? null : project.getId();
        }

        String name() {
            return project != null ? project.getName() : workspace.getName();
        }

        boolean contains(Event event) {
            return project != null ? project.getId().equals(event.getProjectId()) : workspace.getId().equals(event.getWorkspaceId());
        }
    }

    public ExportDtos.Export create(String actorPublicId, ExportScope kind, String scopePublicId, ExportDtos.MultiExportRequest request) {
        Long actorId = userId(actorPublicId);
        Scope scope = scope(actorId, kind, scopePublicId);
        List<Event> events = events(scope, request.eventIds());
        requirePermissions(actorId, events);
        List<ComposeJob> targets = new ArrayList<>();
        for (Event event : events) {
            targets.addAll(exportService.visiblePhotos(event));
            if (targets.size() > exportService.maxPhotos()) {
                break;
            }
        }
        if (targets.isEmpty() || targets.size() > exportService.maxPhotos()) {
            throw new BusinessException(ExportErrorCode.INVALID_SELECTION,
                    "묶을 사진 " + targets.size() + "장 — 1장 이상 " + exportService.maxPhotos() + "장 이하여야 합니다.");
        }
        ExportJob export = exportJobRepository.save(ExportJob.pendingMulti(kind, scope.workspace().getId(), scope.workspace().getPublicId(),
                scope.projectId(), actorId, targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            itemRepository.save(new ExportJobItem(export.getId(), targets.get(i).getId(), i));
        }
        auditLogService.record(actorId, scope.workspace().getId(), null, "EXPORT_CREATED", "EXPORT", export.getPublicId(),
                Map.of("scope", kind.name(), "events", events.size(), "photoCount", targets.size()));
        eventPublisher.publishEvent(new ExportRequestedEvent(export.getId()));
        return view(export);
    }

    @Transactional(readOnly = true)
    public List<ExportDtos.Export> mine(String actorPublicId, ExportScope kind, String scopePublicId) {
        Long actorId = userId(actorPublicId);
        Scope scope = scope(actorId, kind, scopePublicId);
        return exportJobRepository.findByWorkspaceIdAndScopeAndProjectIdAndRequestedByUserIdOrderByIdDesc(
                        scope.workspace().getId(), kind, scope.projectId(), actorId, PageRequest.of(0, 20))
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ExportDtos.Export get(String actorPublicId, ExportScope kind, String scopePublicId, String exportPublicId) {
        Long actorId = userId(actorPublicId);
        return view(own(scope(actorId, kind, scopePublicId), actorId, exportPublicId));
    }

    /**
     * 수령 — 상태·기한·담긴 사진 삭제·담긴 행사마다 BULK_DOWNLOAD 를 다시 본다. 사진이 지워졌으면 그 자리에서 EXPIRED 로 바꾸고 410
     * (그 변경이 롤백되지 않게 BusinessException 으로 롤백하지 않는다 — 판정 전에는 아무것도 쓰지 않는다)
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DownloadUrlResponse download(String actorPublicId, ExportScope kind, String scopePublicId, String exportPublicId) {
        Long actorId = userId(actorPublicId);
        Scope scope = scope(actorId, kind, scopePublicId);
        ExportJob export = own(scope, actorId, exportPublicId);
        switch (export.getStatus()) {
            case PENDING, RUNNING -> throw new BusinessException(ExportErrorCode.NOT_READY);
            case FAILED, EXPIRED -> throw new BusinessException(ExportErrorCode.GONE);
            case DONE -> {
            }
        }
        requirePermissions(actorId, eventRepository.findAllById(countByEvent(export).keySet()));
        if (!LocalDateTime.now(clock).isBefore(export.getExpiresAt()) || itemRepository.containsDeleted(export.getId())) {
            exportService.expire(export);
            throw new BusinessException(ExportErrorCode.GONE);
        }
        String filename = scope.name() + "-" + export.getCreatedAt().atZone(clock.getZone()).format(FILE_DATE) + ".zip";
        String url = fileStorageService.generatePresignedDownloadUrl(export.getS3Key(), filename, storageProperties.download().getTtl());
        auditLogService.record(actorId, scope.workspace().getId(), null, "EXPORT_DOWNLOADED", "EXPORT", export.getPublicId(), null);
        return new DownloadUrlResponse(url, exportService.offset(LocalDateTime.now(clock).plus(storageProperties.download().getTtl())));
    }

    // 범위 관문 — 프로젝트: 공간 OWNER·ADMIN 또는 그 프로젝트 멤버(OPERATOR), 공간: OWNER·ADMIN. 멤버가 아니면 404, 역할이 모자라면 403
    private Scope scope(Long actorId, ExportScope kind, String scopePublicId) {
        if (kind == ExportScope.PROJECT) {
            Project project = projectRepository.findByPublicId(scopePublicId)
                    .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
            Workspace workspace = workspaceService.requireRole(project.getWorkspaceId(), actorId, PROJECT_ROLES);
            boolean admin = workspaceService.roleOf(workspace.getId(), actorId).filter(ADMIN_ROLES::contains).isPresent();
            if (!admin && projectMembershipRepository.findByProjectIdAndUserId(project.getId(), actorId).isEmpty()) {
                throw new BusinessException(GlobalErrorCode.NOT_FOUND);
            }
            return new Scope(kind, workspace, project);
        }
        Workspace workspace = workspaceService.requireRole(scopePublicId, actorId, ADMIN_ROLES);
        return new Scope(kind, workspace, null);
    }

    // 요청한 행사 — 그 범위의 행사만(아니면 400 GEN-002), 요청 순서대로
    private List<Event> events(Scope scope, List<String> eventIds) {
        Map<String, Event> found = eventRepository.findAllByPublicIdIn(eventIds).stream()
                .collect(Collectors.toMap(Event::getPublicId, Function.identity()));
        List<String> outside = eventIds.stream().distinct().filter(id -> found.get(id) == null || !scope.contains(found.get(id))).toList();
        if (!outside.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 범위의 행사가 아닙니다: " + outside);
        }
        return eventIds.stream().distinct().map(found::get).toList();
    }

    // 행사마다 BULK_DOWNLOAD 사진 권한(없으면 403 PHOTO-001)과 이용권 기능(없으면 403 ENT-004) — 빠진 행사를 data.eventIds 로
    private void requirePermissions(Long actorId, List<Event> events) {
        List<String> noPhoto = events.stream()
                .filter(event -> !policy.accessOf(event, actorId).permissions().contains(PhotoPermission.BULK_DOWNLOAD))
                .map(Event::getPublicId).toList();
        if (!noPhoto.isEmpty()) {
            throw new BusinessException(PhotoErrorCode.PERMISSION_REQUIRED, "묶음 권한이 없는 행사: " + noPhoto, Map.of("eventIds", noPhoto));
        }
        List<String> noFeature = events.stream().filter(event -> !entitlementService.allows(event, Feature.BULK_DOWNLOAD))
                .map(Event::getPublicId).toList();
        if (!noFeature.isEmpty()) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_ENTITLED, "이용권에 묶음 기능이 없는 행사: " + noFeature,
                    Map.of("eventIds", noFeature));
        }
    }

    private ExportJob own(Scope scope, Long actorId, String exportPublicId) {
        return exportJobRepository.findByPublicId(exportPublicId)
                .filter(found -> found.getScope() == scope.kind() && found.getWorkspaceId().equals(scope.workspace().getId())
                        && Objects.equals(found.getProjectId(), scope.projectId())
                        && found.getRequestedByUserId().equals(actorId))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private Map<Long, Long> countByEvent(ExportJob export) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        itemRepository.countByEvent(export.getId()).forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    private ExportDtos.Export view(ExportJob export) {
        Map<Long, Long> counts = countByEvent(export);
        Map<Long, Event> events = eventRepository.findAllById(counts.keySet()).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        List<ExportDtos.ExportEvent> included = counts.entrySet().stream()
                .filter(entry -> events.containsKey(entry.getKey()))
                .map(entry -> new ExportDtos.ExportEvent(events.get(entry.getKey()).getPublicId(), events.get(entry.getKey()).getName(),
                        entry.getValue().intValue()))
                .toList();
        return exportService.view(export, included);
    }

    private Long userId(String publicId) {
        return userRepository.findByPublicId(publicId).map(User::getId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }
}
