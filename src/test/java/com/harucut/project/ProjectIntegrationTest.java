package com.harucut.project;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EffectiveSettingsResponse;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.exception.ProjectErrorCode;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.enums.WorkspaceType;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * P3-1 — 프로젝트와 브랜드 기본값 상속을 실제 JPA 로 본다(ADR-0009 §2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("프로젝트·브랜드 기본값 상속 — 통합")
class ProjectIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventExperienceVersionRepository experienceVersionRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private WorkspaceResponse org;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameService.createFrame(owner.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId())
                .frameVersionId();
    }

    @Test
    @DisplayName("프로젝트 만들기·목록·수정(이름·기본값 머지·보관) — 개인 공간·모르는 키·글꼴 \"\" 은 GEN-002")
    void projectCrud() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(),
                new ProjectDtos.CreateRequest("봄 시즌", null));
        assertThat(project.archived()).isFalse();

        ProjectDtos.Project changed = projectService.patch(owner.getPublicId(), project.projectId(), json(Map.of(
                "name", "봄 웨딩 시즌",
                "brandDefaults", Map.of("resultRetentionDays", 60, "theme", Map.of("colors", Map.of("bg", "#101010"))))));
        assertThat(changed.name()).isEqualTo("봄 웨딩 시즌");
        assertThat(changed.brandDefaults().resultRetentionDays()).isEqualTo(60);
        // null 은 JSON 원문으로 — 앱 ObjectMapper 는 Map 의 null 을 트리로 옮기지 않는다(HTTP 본문은 null 이 남는다)
        ProjectDtos.Project cleared = projectService.patch(owner.getPublicId(), project.projectId(),
                objectMapper.readTree("{\"brandDefaults\":{\"resultRetentionDays\":null},\"archived\":true}"));
        assertThat(cleared.brandDefaults().resultRetentionDays()).isNull();
        assertThat(cleared.brandDefaults().theme().colors().bg()).isEqualTo("#101010");   // 다른 키는 그대로
        assertThat(cleared.archived()).isTrue();
        assertThat(projectService.list(owner.getPublicId(), org.workspaceId())).singleElement()
                .satisfies(p -> assertThat(p.archived()).isTrue());

        String personal = workspaceService.listMine(owner.getPublicId()).stream()
                .filter(w -> w.type() == WorkspaceType.PERSONAL).findFirst().orElseThrow().workspaceId();
        assertCode(() -> projectService.create(owner.getPublicId(), personal, new ProjectDtos.CreateRequest("x", null)),
                GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(), json(Map.of("name", "x"))),
                GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(),
                json(Map.of("theme", Map.of("font", "")))), GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("공간 ⊕ 프로젝트 ⊕ 행사: 응답은 유효값, effective-settings 는 경로마다 출처 — null 은 상속, \"\" 는 기본값으로 덮기")
    void inheritance() {
        projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(), json(Map.of(
                "resultRetentionDays", 60,
                "consentItems", List.of(Map.of("code", "ws", "version", 1, "title", "공간 동의", "required", true, "body", "b")),
                "theme", Map.of("colors", Map.of("bg", "#111111", "accent", "#222222"), "copy", Map.of("welcome", "공간 환영")))));
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄",
                objectMapper.convertValue(Map.of("theme", Map.of("colors", Map.of("bg", "#333333"))),
                        com.harucut.event.dto.InheritableSettings.class)));

        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings("행사", "inherit-1",
                null, null, null, null, null, null, null, null, null, null, project.projectId()));
        assertThat(created.settings().projectId()).isEqualTo(project.projectId());
        assertThat(created.settings().resultRetentionDays()).isEqualTo(60);
        assertThat(created.settings().byodEnabled()).isTrue();   // 어디에도 없으면 서버 기본값
        assertThat(created.settings().consentItems()).extracting(EventSettings.ConsentItem::code).containsExactly("ws");
        assertThat(created.settings().theme().colors().bg()).isEqualTo("#333333");
        assertThat(eventRepository.findByPublicId(created.eventId()).orElseThrow().getResultRetentionDays()).isEqualTo(60);   // 컬럼도 유효값

        EffectiveSettingsResponse effective = eventService.effectiveSettings(owner.getPublicId(), created.eventId());
        assertThat(effective.sources()).containsEntry("resultRetentionDays", "WORKSPACE")
                .containsEntry("theme.colors.bg", "PROJECT").containsEntry("theme.colors.accent", "WORKSPACE")
                .containsEntry("name", "EVENT").containsEntry("projectId", "EVENT")
                .doesNotContainKeys("byodEnabled", "frames");

        // 직접 값 → null 로 상속 되돌리기, 문구 "" 로 공간 문구 대신 FE 기본 문구
        EventResponse direct = patch(created, Map.of("resultRetentionDays", 7,
                "theme", Map.of("copy", Map.of("welcome", ""))));
        assertThat(direct.settings().resultRetentionDays()).isEqualTo(7);
        assertThat(direct.settings().theme().copy() == null ? null : direct.settings().theme().copy().welcome()).isNull();
        assertThat(direct.settings().theme().colors().bg()).isEqualTo("#333333");   // 테마 안에서 빠진 키는 위 단계 값
        assertThat(eventService.effectiveSettings(owner.getPublicId(), created.eventId()).sources())
                .containsEntry("theme.copy.welcome", "EVENT").containsEntry("resultRetentionDays", "EVENT");
        assertThat(patch(direct, "{\"resultRetentionDays\":null}").settings().resultRetentionDays()).isEqualTo(60);
    }

    @Test
    @DisplayName("게시하면 굳는다 — 그 뒤 프로젝트 기본값을 바꿔도 발행본·운영 값은 그대로, 다시 게시하면 들어간다")
    void publishFreezes() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        projectService.patch(owner.getPublicId(), project.projectId(), json(Map.of("brandDefaults", Map.of("resultRetentionDays", 45,
                "consentItems", List.of(Map.of("code", "pj", "version", 1, "title", "t", "required", true, "body", "b"))))));
        EventResponse event = patch(ready("freeze-1", project.projectId()), "{\"consentItems\":null}");   // 동의 항목은 프로젝트에서
        eventService.publish(owner.getPublicId(), event.eventId());
        Event live = eventRepository.findByPublicId(event.eventId()).orElseThrow();
        assertThat(live.getResultRetentionDays()).isEqualTo(45);
        String published = experienceVersionRepository.findById(live.getCurrentExperienceVersionId()).orElseThrow().getSettingsJson();
        assertThat(published).contains("\"pj\"");

        projectService.patch(owner.getPublicId(), project.projectId(), json(Map.of("brandDefaults", Map.of("resultRetentionDays", 90,
                "consentItems", List.of(Map.of("code", "pj2", "version", 1, "title", "t", "required", true, "body", "b"))))));
        entityManager.flush();
        entityManager.clear();
        Event still = eventRepository.findByPublicId(event.eventId()).orElseThrow();
        assertThat(still.getResultRetentionDays()).isEqualTo(45);
        assertThat(experienceVersionRepository.findById(still.getCurrentExperienceVersionId()).orElseThrow().getSettingsJson()).contains("\"pj\"");

        eventService.publish(owner.getPublicId(), event.eventId());
        entityManager.flush();
        entityManager.clear();
        Event republished = eventRepository.findByPublicId(event.eventId()).orElseThrow();
        assertThat(republished.getResultRetentionDays()).isEqualTo(90);
        EventExperienceVersion v2 = experienceVersionRepository.findById(republished.getCurrentExperienceVersionId()).orElseThrow();
        assertThat(v2.getVersionNo()).isEqualTo(2);
        assertThat(v2.getSettingsJson()).contains("\"pj2\"");
    }

    @Test
    @DisplayName("보관된 프로젝트는 PROJECT-001(만들기·지정·복제), 게시 뒤 프로젝트 변경은 PROJECT-002, 다른 공간 프로젝트는 GEN-002")
    void projectRules() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        ProjectDtos.Project other = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("가을", null));
        EventResponse event = ready("rules-1", project.projectId());

        projectService.patch(owner.getPublicId(), project.projectId(), json(Map.of("archived", true)));
        // 이미 그 프로젝트인 행사는 그대로 둔다(다시 같은 값을 보내도 된다)
        assertThat(patch(event, Map.of("projectId", project.projectId())).settings().projectId()).isEqualTo(project.projectId());
        assertCode(() -> eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings("x", "rules-2",
                null, null, null, null, null, null, null, null, null, null, project.projectId())), ProjectErrorCode.PROJECT_ARCHIVED);
        assertCode(() -> eventService.cloneEvent(owner.getPublicId(), event.eventId(), "복제", "rules-3"), ProjectErrorCode.PROJECT_ARCHIVED);

        eventService.publish(owner.getPublicId(), event.eventId());
        EventResponse live = eventService.get(owner.getPublicId(), event.eventId());
        assertCode(() -> patch(live, Map.of("projectId", other.projectId())), ProjectErrorCode.EVENT_PUBLISHED);
        assertCode(() -> patch(live, "{\"projectId\":null}"), ProjectErrorCode.EVENT_PUBLISHED);

        User stranger = userRepository.save(User.localUser("s@harucut.com", "e", "s"));
        WorkspaceResponse strangerOrg = workspaceService.createOrganization(stranger.getPublicId(), "남의 공간");
        ProjectDtos.Project foreign = projectService.create(stranger.getPublicId(), strangerOrg.workspaceId(),
                new ProjectDtos.CreateRequest("남의 것", null));
        assertCode(() -> eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings("x", "rules-4",
                null, null, null, null, null, null, null, null, null, null, foreign.projectId())), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> projectService.patch(owner.getPublicId(), foreign.projectId(), json(Map.of("name", "x"))), GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("맞지 않는 값({\"resultRetentionDays\":\"invalid\"} 등)은 500 이 아니라 400 GEN-002 — 공간·프로젝트 기본값, 행사 PATCH")
    void malformedValuesAreBadRequest() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings("행사", "malformed-1",
                null, null, null, null, null, null, null, null, null, null, null));

        assertCode(() -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(),
                objectMapper.readTree("{\"resultRetentionDays\":\"invalid\"}")), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> projectService.patch(owner.getPublicId(), project.projectId(),
                objectMapper.readTree("{\"brandDefaults\":{\"allowedLayouts\":\"CLASSIC\"}}")), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> patch(event, "{\"resultRetentionDays\":\"invalid\"}"), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> patch(event, "{\"captureStartsAt\":\"not-a-time\"}"), GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    private EventResponse ready(String slug, String projectId) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings("행사", slug,
                null, null, null, null, null, null, null, null, null, null, projectId));
        return patch(created, Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))));
    }

    private EventResponse patch(EventResponse event, Map<String, Object> settings) {
        int version = eventService.get(owner.getPublicId(), event.eventId()).settingsVersion();
        return eventService.patch(owner.getPublicId(), event.eventId(),
                new EventPatchRequest(version, objectMapper.valueToTree(settings)));
    }

    // null 을 담은 패치는 JSON 원문으로
    private EventResponse patch(EventResponse event, String settingsJson) {
        int version = eventService.get(owner.getPublicId(), event.eventId()).settingsVersion();
        return eventService.patch(owner.getPublicId(), event.eventId(),
                new EventPatchRequest(version, (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(settingsJson)));
    }

    private JsonNode json(Map<String, Object> value) {
        return objectMapper.valueToTree(value);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
