package com.harucut.event.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.dto.EffectiveSettingsResponse;
import com.harucut.event.dto.InheritableSettings;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.entity.EventFrame;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventFrameRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.project.entity.Project;
import com.harucut.project.exception.ProjectErrorCode;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 행사 만들기·설정·게시·운영 상태 (ADR-0007 §4). 행사는 조직 공간의 OWNER·ADMIN 이 다룬다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class EventService {

    private static final Set<WorkspaceRole> OPERATOR_ROLES = Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,40}$");

    private final EventRepository eventRepository;
    private final EventExperienceVersionRepository experienceVersionRepository;
    private final EventFrameRepository eventFrameRepository;
    private final FrameVersionRepository frameVersionRepository;
    private final FrameRepository frameRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;
    private final EventGrantService eventGrantService;
    private final ThemeResolver themeResolver;
    private final EventFeatures eventFeatures;
    private final EntitlementService entitlementService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ProjectRepository projectRepository;
    private final EffectiveSettingsResolver settingsResolver;

    public EventResponse create(String publicId, String workspacePublicId, EventSettings settings) {
        User user = getUser(publicId);
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), OPERATOR_ROLES);
        if (workspace.isPersonal()) {
            // 개인 공간은 회원 탈퇴 때 통째로 지워진다 — 행사 사진을 개인 탈퇴로 연쇄 삭제하지 않는다(R5 14절)
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "행사는 조직 공간에만 만들 수 있습니다.");
        }
        requireText(settings.name(), "name");
        String slug = requireSlug(settings.slug());
        if (eventRepository.existsBySlug(slug)) {
            throw new BusinessException(EventErrorCode.SLUG_TAKEN);
        }

        Event event = Event.draft(workspace.getId(), slug, settings.name().strip(), user.getId(),
                objectMapper.writeValueAsString(InheritableSettings.empty()));
        // 만들 때는 보낸 값만 행사의 직접 값으로 둔다 — 안 보낸 필드(null)는 공간·프로젝트 기본값(없으면 서버 기본값)을 따른다
        ObjectNode sent = objectMapper.valueToTree(settings);
        List<String> nulls = sent.propertyNames().stream().filter(name -> sent.get(name).isNull()).toList();
        nulls.forEach(sent::remove);
        apply(event, sent, true, workspace);
        Event saved = eventRepository.save(event);
        eventGrantService.grantCreator(saved.getId(), user.getId());
        auditLogService.record(user.getId(), workspace.getId(), saved.getId(), "EVENT_CREATED", "EVENT", saved.getPublicId(), null);
        return response(saved, workspace);
    }

    /**
     * 행사 복제 (ADR-0008 §3, R5 7절) — 설정 초안(레이아웃·제공 프레임·동의 항목·테마)과 참가 방식·보관 일수만 새 DRAFT 로.
     * 일정은 복제하지 않는다(다시 정하고 게시). 사진·참가자·세션·결과 토큰·고객 초대·권한·동의 기록·부스는 복제하지 않는다.
     */
    public EventResponse cloneEvent(String publicId, String eventId, String name, String slug) {
        Operated operated = operate(publicId, eventId);
        Event source = operated.event();
        String newSlug = requireSlug(slug);
        if (eventRepository.existsBySlug(newSlug)) {
            throw new BusinessException(EventErrorCode.SLUG_TAKEN);
        }
        Event copy = Event.draft(source.getWorkspaceId(), newSlug, requireText(name, "name"), operated.user().getId(),
                source.getSettingsDraft());
        copy.configure(source.getTimezone(), source.isByodEnabled(), source.isBoothEnabled(), source.getResultRetentionDays());
        if (source.getProjectId() != null) {
            // 같은 프로젝트에 새 행사를 넣는 것이다 — 보관된 프로젝트면 PROJECT-001
            if (projectRepository.findById(source.getProjectId()).map(Project::isArchived).orElse(false)) {
                throw new BusinessException(ProjectErrorCode.PROJECT_ARCHIVED);
            }
            copy.assignProject(source.getProjectId());
        }
        Event saved = eventRepository.save(copy);
        eventGrantService.grantCreator(saved.getId(), operated.user().getId());
        auditLogService.record(operated.user().getId(), saved.getWorkspaceId(), saved.getId(), "EVENT_CLONED", "EVENT",
                saved.getPublicId(), Map.of("from", source.getPublicId()));
        return response(saved, operated.workspace());
    }

    @Transactional(readOnly = true)
    public List<EventResponse> list(String publicId, String workspacePublicId) {
        User user = getUser(publicId);
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), OPERATOR_ROLES);
        return eventRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .map(event -> response(event, workspace))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse get(String publicId, String eventId) {
        Operated operated = operate(publicId, eventId);
        return response(operated.event(), operated.workspace());
    }

    public EventResponse patch(String publicId, String eventId, EventPatchRequest request) {
        Operated operated = operate(publicId, eventId);
        Event event = operated.event();
        if (request.settingsVersion() != event.getSettingsVersion()) {
            throw versionConflict(event);
        }
        apply(event, request.settings(), false, operated.workspace());
        // 바뀐 필드 이름만 남긴다 — 값(동의 문구·테마 등)은 길고 발행본에 남는다
        auditLogService.record(operated.user().getId(), event.getWorkspaceId(), event.getId(), "EVENT_SETTINGS_CHANGED",
                "EVENT", event.getPublicId(), Map.of("fields", request.settings().propertyNames().stream().sorted().toList()));
        return response(flush(event), operated.workspace());
    }

    public EventResponse publish(String publicId, String eventId) {
        Operated operated = operate(publicId, eventId);
        Event event = operated.event();
        // 같은 행사의 게시를 줄 세운다 — 행사 행을 쓰기 잠금으로 다시 읽고(refresh), 그사이 다른 저장·게시가 커밋됐으면 409.
        // 잠그지 않으면 두 게시의 경험 버전 insert(FK 공유 잠금)와 행사 UPDATE 가 엇갈려 교착·유니크 위반으로 500 이 난다.
        // 잠금 순서는 늘 「공간 → 행사」: 동시 운영 수 검사(entitlementViolations)가 공간을 잠근 뒤 공간의 행사 행들을 잠금 읽기로 훑으므로,
        // 행사를 먼저 잠그면 같은 공간의 다른 게시와 서로를 기다려 교착된다
        workspaceRepository.lockById(event.getWorkspaceId());
        int seenVersion = event.getSettingsVersion();
        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
        if (event.getSettingsVersion() != seenVersion) {
            throw new BusinessException(EventErrorCode.SETTINGS_VERSION_CONFLICT, "동시 게시 충돌: " + event.getPublicId());
        }
        entitlementService.requireApplicable(event);   // 적용 이용권이 없으면 403 ENT-001
        // 게시 = 지금의 유효 설정(공간 ⊕ 프로젝트 ⊕ 행사)을 발행본에 굳힌다. 컬럼(지금 적용되는 값)도 유효값으로 맞춘다 —
        // 그 뒤 상위 기본값이 바뀌어도 이 행사는 다시 게시하기 전까지 그대로다(ADR-0009 원칙 2)
        EffectiveSettingsResolver.Effective effective = effective(event, operated.workspace()).effective();
        event.configure(effective.timezone(), effective.byodEnabled(), effective.boothEnabled(), effective.resultRetentionDays());
        EventExperienceDocument draft = document(effective);

        List<Map<String, String>> violations = new ArrayList<>();
        List<FrameVersion> versions = validateForPublish(event, draft, violations);
        if (!violations.isEmpty()) {
            throw new BusinessException(EventErrorCode.PUBLISH_INVALID, "게시 검증 실패: " + violations,
                    Map.of("violations", violations));
        }

        EventExperienceVersion version;
        try {
            version = experienceVersionRepository.saveAndFlush(EventExperienceVersion.publish(
                    event.getId(), experienceVersionRepository.findMaxVersionNo(event.getId()) + 1,
                    write(draft.published()), operated.user().getId()));
        } catch (DataIntegrityViolationException e) {
            // 위 잠금으로 같은 행사의 게시는 줄을 서지만, 번호(uk_event_experience_version_no)가 겹치면 같은 409 로 끝낸다(안전망)
            throw new BusinessException(EventErrorCode.SETTINGS_VERSION_CONFLICT, "동시 게시 충돌: " + event.getPublicId());
        }
        for (int i = 0; i < draft.frames().size(); i++) {
            EventSettings.FrameRef ref = draft.frames().get(i);
            eventFrameRepository.save(new EventFrame(version.getId(), versions.get(i).getId(), ref.layoutType(),
                    ref.sortOrder() == null ? i : ref.sortOrder(), Boolean.TRUE.equals(ref.isDefault())));
        }
        event.publish(version.getId());
        auditLogService.record(operated.user().getId(), event.getWorkspaceId(), event.getId(), "EVENT_PUBLISHED", "EVENT",
                event.getPublicId(), Map.of("experienceVersion", version.getVersionNo()));
        return response(flush(event), operated.workspace());
    }

    public EventResponse pause(String publicId, String eventId) {
        return transition(publicId, eventId, Event::pause, "EVENT_PAUSED");
    }

    public EventResponse resume(String publicId, String eventId) {
        return transition(publicId, eventId, Event::resume, "EVENT_RESUMED");
    }

    public EventResponse closeCapture(String publicId, String eventId) {
        return transition(publicId, eventId, Event::closeCapture, "EVENT_CAPTURE_CLOSED");
    }

    // ── 설정 적용 ──────────────────────

    // settings 에 있는 필드만 바꾼다. creating 이면 name·slug 는 이미 정해졌다.
    // 상속 가능한 필드는 행사의 「직접 값」(settings_draft)에 넣고 뺀다 — null 이면 빼서 위 단계 값을 따른다(ADR-0009 §2-1).
    // 컬럼(지금 적용되는 값)은 만들 때·프로젝트를 바꿀 때·그 필드를 직접 바꿀 때만 유효값으로 다시 계산한다 — 다른 필드를 저장했다고
    // 그사이 바뀐 상위 기본값이 운영 중 행사에 끌려 들어오지 않게
    private void apply(Event event, ObjectNode settings, boolean creating, Workspace workspace) {
        if (!creating && settings.has("name")) {
            event.rename(requireText(settings.get("name").isNull() ? null : settings.get("name").asString(), "name"));
        }
        if (!creating && settings.has("slug")) {
            String slug = requireSlug(settings.get("slug").isNull() ? null : settings.get("slug").asString());
            if (!slug.equals(event.getSlug())) {
                if (event.getCurrentExperienceVersionId() != null) {
                    throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                            "게시한 행사의 slug 는 바꿀 수 없습니다 — 배포한 링크·QR 이 깨집니다.");
                }
                if (eventRepository.existsBySlug(slug)) {
                    throw new BusinessException(EventErrorCode.SLUG_TAKEN);
                }
                event.changeSlug(slug);
            }
        }

        EventSettings typed = objectMapper.treeToValue(settings, EventSettings.class);
        LocalDateTime starts = settings.has("captureStartsAt") ? local(typed.captureStartsAt()) : event.getCaptureStartsAt();
        LocalDateTime ends = settings.has("captureEndsAt") ? local(typed.captureEndsAt()) : event.getCaptureEndsAt();
        if (starts != null && ends != null && !ends.isAfter(starts)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "촬영 종료는 시작보다 뒤여야 합니다.");
        }
        event.schedule(starts, ends);

        // 검사를 다 마친 뒤에 바꾼다 — 거절된 요청이 엔티티에 흔적을 남기지 않게(같은 트랜잭션의 다음 작업이 보지 않게)
        Project project = settings.has("projectId") ? projectFor(event, settings.get("projectId"))
                : event.getProjectId() == null ? null : projectRepository.findById(event.getProjectId()).orElse(null);
        Long projectId = project == null ? null : project.getId();
        boolean projectChanged = !Objects.equals(projectId, event.getProjectId());

        Set<String> touched = new HashSet<>(EffectiveSettingsResolver.KEYS);
        touched.retainAll(settings.propertyNames());
        String directJson = settingsResolver.merge(event.getSettingsDraft(), settings, false);

        boolean all = creating || projectChanged;
        EffectiveSettingsResolver.Effective effective = settingsResolver.resolve(workspace.getBrandDefaults(),
                project == null ? null : project.getBrandDefaults(), directJson);
        String timezone = all || touched.contains("timezone") ? effective.timezone() : event.getTimezone();
        boolean byod = all || touched.contains("byodEnabled") ? effective.byodEnabled() : event.isByodEnabled();
        boolean booth = all || touched.contains("boothEnabled") ? effective.boothEnabled() : event.isBoothEnabled();
        int retention = all || touched.contains("resultRetentionDays") ? effective.resultRetentionDays()
                : event.getResultRetentionDays();
        if (all || touched.contains("resultRetentionDays")) {
            // 이용권의 보관 일수 상한 — 게시 뒤 PATCH 로도 넘지 못하게(보관·정리는 이 값을 바로 읽는다)
            entitlementService.applicable(event).map(e -> e.getRetentionDays()).filter(Objects::nonNull)
                    .filter(cap -> retention > cap)
                    .ifPresent(cap -> {
                        throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                                "이용권의 보관 일수 상한(" + cap + "일)을 넘습니다.");
                    });
        }
        event.assignProject(projectId);
        event.replaceSettingsDraft(directJson);
        event.configure(timezone, byod, booth, retention);
    }

    // 행사의 프로젝트 지정·해제 검사 — 같은 공간·보관되지 않은 프로젝트만, 게시 뒤에는 바꾸지 않는다. 지정할 프로젝트(해제면 null)
    private Project projectFor(Event event, JsonNode value) {
        Project project = null;
        if (!value.isNull()) {
            project = projectRepository.findByPublicId(value.asString())
                    .filter(p -> p.getWorkspaceId().equals(event.getWorkspaceId()))
                    .orElseThrow(() -> new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "이 공간의 프로젝트가 아닙니다."));
            if (!project.getId().equals(event.getProjectId()) && project.isArchived()) {
                throw new BusinessException(ProjectErrorCode.PROJECT_ARCHIVED);
            }
        }
        Long projectId = project == null ? null : project.getId();
        if (!Objects.equals(projectId, event.getProjectId()) && event.getCurrentExperienceVersionId() != null) {
            throw new BusinessException(ProjectErrorCode.EVENT_PUBLISHED);
        }
        return project;
    }

    // ── 게시 검증 ──────────────────────

    // 실패를 하나에서 멈추지 않고 모두 모은다 — 운영자가 한 번에 고칠 수 있게. 순서대로 대응하는 발행본을 돌려준다
    private List<FrameVersion> validateForPublish(Event event, EventExperienceDocument draft,
                                                  List<Map<String, String>> violations) {
        if (!event.isByodEnabled() && !event.isBoothEnabled()) {
            violations.add(violation("byodEnabled", "개인 휴대폰·부스 중 하나 이상을 허용해야 한다"));
        }
        List<FrameType> layouts = orEmpty(draft.allowedLayouts());
        if (layouts.isEmpty()) {
            violations.add(violation("allowedLayouts", "허용 레이아웃이 하나 이상 있어야 한다"));
        }

        List<FrameVersion> versions = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<FrameType> covered = new HashSet<>();
        int defaults = 0;
        List<EventSettings.FrameRef> frames = orEmpty(draft.frames());
        for (int i = 0; i < frames.size(); i++) {
            EventSettings.FrameRef ref = frames.get(i);
            String field = "frames[" + i + "]";
            if (ref == null || ref.frameVersionId() == null || ref.layoutType() == null) {
                violations.add(violation(field, "frameVersionId·layoutType 이 필요하다"));
                versions.add(null);
                continue;
            }
            if (!seen.add(ref.frameVersionId())) {
                violations.add(violation(field, "같은 발행본을 두 번 넣었다"));
            }
            if (Boolean.TRUE.equals(ref.isDefault())) {
                defaults++;
            }
            FrameVersion version = frameVersionRepository.findById(ref.frameVersionId()).orElse(null);
            versions.add(version);
            if (version == null) {
                violations.add(violation(field, "없는 프레임 발행본이다"));
                continue;
            }
            if (version.getFrameType() != ref.layoutType()) {
                violations.add(violation(field, "발행본의 레이아웃(" + version.getFrameType() + ")과 다르다"));
            }
            if (!layouts.contains(ref.layoutType())) {
                violations.add(violation(field, "허용하지 않은 레이아웃의 프레임이다"));
            }
            Frame frame = frameRepository.findById(version.getFrameId()).orElse(null);
            if (frame == null || !(frame.isSystem() || Objects.equals(frame.getWorkspaceId(), event.getWorkspaceId()))) {
                violations.add(violation(field, "이 공간의 프레임이나 시스템 프레임만 쓸 수 있다"));
            }
            covered.add(ref.layoutType());
        }
        for (FrameType layout : layouts) {
            if (!covered.contains(layout)) {
                violations.add(violation("allowedLayouts", layout + " 에 연결된 프레임이 없다"));
            }
        }
        if (frames.isEmpty()) {
            violations.add(violation("frames", "제공 프레임이 하나 이상 있어야 한다 — 기본 카탈로그로 대체하지 않는다"));
        } else if (defaults != 1) {
            violations.add(violation("frames", "기본 프레임은 정확히 하나여야 한다(지금 " + defaults + "개)"));
        }

        List<EventSettings.ConsentItem> consents = orEmpty(draft.consentItems());
        if (consents.isEmpty()) {
            violations.add(violation("consentItems", "사진 수신 주체·목적·보관을 안내하는 동의 항목이 하나 이상 있어야 한다"));
        }
        Set<String> codes = new HashSet<>();
        boolean anyRequired = false;
        for (int i = 0; i < consents.size(); i++) {
            EventSettings.ConsentItem item = consents.get(i);
            String field = "consentItems[" + i + "]";
            if (item == null || isBlank(item.code()) || isBlank(item.title()) || isBlank(item.body())
                    || item.version() == null || item.version() < 1) {
                violations.add(violation(field, "code·title·body 와 1 이상의 version 이 필요하다"));
                continue;
            }
            if (!codes.add(item.code())) {
                violations.add(violation(field, "같은 code 가 두 번 있다"));
            }
            anyRequired |= Boolean.TRUE.equals(item.required());
        }
        if (!consents.isEmpty() && !anyRequired) {
            violations.add(violation("consentItems", "필수 동의 항목이 하나 이상 있어야 한다"));
        }
        violations.addAll(themeResolver.violations(draft.theme(), event.getWorkspaceId(),
                eventFeatures.allows(event, Feature.CUSTOM_THEME), eventFeatures.allows(event, Feature.WHITE_LABEL)));
        violations.addAll(entitlementViolations(event));
        return versions;
    }

    // 적용 이용권의 조건 — 운영 중 행사 수와 보관 일수 상한. 이용권 자체가 없으면 게시 전에 ENT-001 로 끊는다
    private List<Map<String, String>> entitlementViolations(Event event) {
        List<Map<String, String>> violations = new ArrayList<>();
        entitlementService.applicable(event).ifPresent(entitlement -> {
            // 처음 게시(DRAFT → LIVE)만 운영 중 행사 수를 늘린다(촬영 종료 뒤 재게시는 LIVE 로 돌아가지 않는다).
            // 같은 공간에서 동시에 게시하면 둘 다 0 을 읽을 수 있어, 공간 행을 잠근 뒤 잠금 읽기로 센다(스냅샷 읽기는 놓친다)
            if (entitlement.getMaxLiveEvents() != null && event.getStatus() == EventStatus.DRAFT
                    && workspaceRepository.lockById(event.getWorkspaceId()).isPresent()
                    && eventRepository.lockLiveEventIds(event.getWorkspaceId()).size() >= entitlement.getMaxLiveEvents()) {
                violations.add(violation("status", "이용권의 동시 운영 행사 수(" + entitlement.getMaxLiveEvents() + ")를 넘는다"));
            }
            if (entitlement.getRetentionDays() != null && event.getResultRetentionDays() > entitlement.getRetentionDays()) {
                violations.add(violation("resultRetentionDays", "이용권의 보관 일수 상한(" + entitlement.getRetentionDays() + "일)을 넘는다"));
            }
        });
        return violations;
    }

    // ── 공통 ──────────────────────

    private EventResponse transition(String publicId, String eventId, java.util.function.Consumer<Event> action,
                                     String auditAction) {
        Operated operated = operate(publicId, eventId);
        try {
            action.accept(operated.event());
        } catch (IllegalStateException e) {
            throw new BusinessException(EventErrorCode.CAPTURE_NOT_OPEN, e.getMessage());
        }
        auditLogService.record(operated.user().getId(), operated.event().getWorkspaceId(), operated.event().getId(),
                auditAction, "EVENT", operated.event().getPublicId(), null);
        return response(flush(operated.event()), operated.workspace());
    }

    /** 부스 기기 관리처럼 행사를 운영하는 다른 기능의 관문 — 그 공간의 OWNER·ADMIN. 아니면 404 */
    @Transactional(readOnly = true)
    public Event requireOperable(String userPublicId, String eventPublicId) {
        return operate(userPublicId, eventPublicId).event();
    }

    /** 내부 id 판 — 기기처럼 행사 id 를 이미 가진 행에서 */
    @Transactional(readOnly = true)
    public Event requireOperable(String userPublicId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        workspaceService.requireRole(event.getWorkspaceId(), getUser(userPublicId).getId(), OPERATOR_ROLES);
        return event;
    }

    private Operated operate(String publicId, String eventId) {
        User user = getUser(publicId);
        Event event = eventRepository.findByPublicId(eventId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Workspace workspace = workspaceService.requireRole(event.getWorkspaceId(), user.getId(), OPERATOR_ROLES);
        return new Operated(user, event, workspace);
    }

    private Event flush(Event event) {
        try {
            return eventRepository.saveAndFlush(event);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(EventErrorCode.SETTINGS_VERSION_CONFLICT, "동시 저장 충돌: " + event.getPublicId());
        }
    }

    private BusinessException versionConflict(Event event) {
        return new BusinessException(EventErrorCode.SETTINGS_VERSION_CONFLICT,
                "settingsVersion 불일치: 현재 " + event.getSettingsVersion(),
                Map.of("currentSettingsVersion", event.getSettingsVersion()));
    }

    /** 유효 설정 미리보기(ADR-0009 §2-2) — 게시와 같은 계산, 값이 있는 경로마다 출처 */
    @Transactional(readOnly = true)
    public EffectiveSettingsResponse effectiveSettings(String publicId, String eventId) {
        Operated operated = operate(publicId, eventId);
        Event event = operated.event();
        Resolved resolved = effective(event, operated.workspace());
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("name", EffectiveSettingsResolver.Source.EVENT.name());
        sources.put("slug", EffectiveSettingsResolver.Source.EVENT.name());
        if (event.getCaptureStartsAt() != null) {
            sources.put("captureStartsAt", EffectiveSettingsResolver.Source.EVENT.name());
        }
        if (event.getCaptureEndsAt() != null) {
            sources.put("captureEndsAt", EffectiveSettingsResolver.Source.EVENT.name());
        }
        if (resolved.project() != null) {
            sources.put("projectId", EffectiveSettingsResolver.Source.EVENT.name());
        }
        resolved.effective().sources().forEach((path, source) -> sources.put(path, source.name()));
        return new EffectiveSettingsResponse(settings(event, resolved), sources);
    }

    EventResponse response(Event event, Workspace workspace) {
        Integer currentVersion = event.getCurrentExperienceVersionId() == null ? null
                : experienceVersionRepository.findById(event.getCurrentExperienceVersionId())
                .map(EventExperienceVersion::getVersionNo).orElse(null);
        // 설정 = 지금 게시하면 들어갈 유효값(공간 ⊕ 프로젝트 ⊕ 행사). 어디서 왔는지는 effective-settings 가 준다
        EventSettings settings = settings(event, effective(event, workspace));
        return new EventResponse(event.getPublicId(), workspace.getPublicId(), event.getStatus(), event.isPaused(),
                event.captureOpen(LocalDateTime.now(clock)), event.getSettingsVersion(), settings, currentVersion,
                "/e/" + event.getSlug());
    }

    private record Resolved(Project project, EffectiveSettingsResolver.Effective effective) {
    }

    private Resolved effective(Event event, Workspace workspace) {
        Project project = event.getProjectId() == null ? null : projectRepository.findById(event.getProjectId()).orElse(null);
        return new Resolved(project, settingsResolver.resolve(workspace.getBrandDefaults(),
                project == null ? null : project.getBrandDefaults(), event.getSettingsDraft()));
    }

    private EventSettings settings(Event event, Resolved resolved) {
        EffectiveSettingsResolver.Effective effective = resolved.effective();
        InheritableSettings values = effective.values();
        return new EventSettings(event.getName(), event.getSlug(),
                offset(event.getCaptureStartsAt()), offset(event.getCaptureEndsAt()), effective.timezone(),
                effective.byodEnabled(), effective.boothEnabled(), effective.resultRetentionDays(),
                effective.orEmpty(values.allowedLayouts()), effective.orEmpty(values.frames()),
                effective.orEmpty(values.consentItems()), values.theme(),
                resolved.project() == null ? null : resolved.project().getPublicId());
    }

    private static EventExperienceDocument document(EffectiveSettingsResolver.Effective effective) {
        InheritableSettings values = effective.values();
        return new EventExperienceDocument(effective.orEmpty(values.allowedLayouts()), effective.orEmpty(values.frames()),
                effective.orEmpty(values.consentItems()), values.theme(), null);
    }

    private String write(EventExperienceDocument document) {
        return objectMapper.writeValueAsString(document);
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private static String requireText(String value, String field) {
        if (isBlank(value) || value.strip().length() > 100) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, field + " 은 1~100자여야 합니다.");
        }
        return value.strip();
    }

    private static String requireSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "slug 는 영문 소문자·숫자·하이픈 3~40자여야 합니다.");
        }
        return slug;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> violation(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private record Operated(User user, Event event, Workspace workspace) {
    }
}
