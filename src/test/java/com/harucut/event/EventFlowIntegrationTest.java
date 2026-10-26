package com.harucut.event;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.EventKind;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.repository.EventFrameRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.support.TestEntitlements;
import com.harucut.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 행사 운영 흐름의 실제 JPA 경로 — 조직 공간 → 그 공간의 프레임 발행 → 행사 초안 → 설정 → 게시 검증 → 운영 상태.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("행사 운영 — 통합")
class EventFlowIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private EventFrameRepository eventFrameRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private EntitlementRepository entitlementRepository;

    @Autowired private WorkspaceRepository workspaceRepository;

    @MockitoBean
    private FileStorageService fileStorageService;

    private User operator;
    private WorkspaceResponse org;

    @BeforeEach
    void setUp() {
        operator = userRepository.save(User.localUser("op@harucut.com", "encoded", "운영자"));
        org = workspaceService.createOrganization(operator.getPublicId(), "웨딩팀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
    }

    @Test
    @DisplayName("초안 → 설정 → 게시(LIVE, 경험 v1, 프레임 연결) → 일시중지·재개·촬영 종료")
    void createConfigurePublishOperate() {
        Long frameVersionId = publishedFrame(FrameType.CLASSIC);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                settings("민지 ♥ 준호", "wedding-1027"));
        assertThat(created.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(created.joinUrlPath()).isEqualTo("/e/wedding-1027");
        assertThat(created.settings().byodEnabled()).isTrue();

        EventResponse configured = eventService.patch(operator.getPublicId(), created.eventId(),
                new EventPatchRequest(created.settingsVersion(), patch(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC",
                                "sortOrder", 1, "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo-collection", "version", 1,
                                "title", "사진 수집 동의", "required", true, "body", "신랑신부가 사진을 받습니다."))))));

        EventResponse published = eventService.publish(operator.getPublicId(), created.eventId());
        assertThat(published.status()).isEqualTo(EventStatus.LIVE);
        assertThat(published.currentExperienceVersion()).isEqualTo(1);
        assertThat(published.captureOpen()).isTrue();
        assertThat(eventFrameRepository.findAll()).singleElement()
                .satisfies(frame -> assertThat(frame.getFrameVersionId()).isEqualTo(frameVersionId));

        assertThat(eventService.pause(operator.getPublicId(), created.eventId()).captureOpen()).isFalse();
        assertThat(eventService.resume(operator.getPublicId(), created.eventId()).captureOpen()).isTrue();
        EventResponse closed = eventService.closeCapture(operator.getPublicId(), created.eventId());
        assertThat(closed.status()).isEqualTo(EventStatus.CAPTURE_CLOSED);
        assertThat(closed.captureOpen()).isFalse();
        assertThat(configured.settingsVersion()).isLessThan(closed.settingsVersion());
    }

    @Test
    @DisplayName("빈 설정 게시는 EVENT-003 이고 위반을 모두 돌려준다 — 기본 카탈로그로 대체하지 않는다")
    void publishCollectsViolations() {
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), settings("빈 행사", "empty-event"));

        assertThatThrownBy(() -> eventService.publish(operator.getPublicId(), created.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(EventErrorCode.PUBLISH_INVALID);
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field"))
                            .contains("allowedLayouts", "frames", "consentItems");
                });
    }

    @Test
    @DisplayName("다른 레이아웃의 발행본·두 개의 기본 프레임은 게시 위반이다")
    void publishRejectsLayoutMismatchAndTwoDefaults() {
        Long classic = publishedFrame(FrameType.CLASSIC);
        Long grid = publishedFrame(FrameType.GRID);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), settings("행사", "mismatch-event"));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(), patch(Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(
                        Map.of("frameVersionId", classic, "layoutType", "CLASSIC", "isDefault", true),
                        Map.of("frameVersionId", grid, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));

        assertThatThrownBy(() -> eventService.publish(operator.getPublicId(), created.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    String violations = String.valueOf(e.getData());
                    assertThat(violations).contains("frames[1]").contains("기본 프레임은 정확히 하나");
                });
    }

    @Test
    @DisplayName("개인 공간에는 행사를 만들 수 없다")
    void personalWorkspaceRejected() {
        String personal = workspaceService.listMine(operator.getPublicId()).stream()
                .filter(w -> w.type() == com.harucut.workspace.enums.WorkspaceType.PERSONAL)
                .findFirst().orElseThrow().workspaceId();

        assertThatThrownBy(() -> eventService.create(operator.getPublicId(), personal, settings("x", "personal-event")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("slug 중복은 EVENT-004, 옛 settingsVersion 은 EVENT-005, 멤버가 아니면 404")
    void conflictsAndAccess() {
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), settings("a", "taken-slug"));
        assertThatThrownBy(() -> eventService.create(operator.getPublicId(), org.workspaceId(), settings("b", "taken-slug")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EventErrorCode.SLUG_TAKEN);

        eventService.patch(operator.getPublicId(), created.eventId(),
                new EventPatchRequest(created.settingsVersion(), patch(Map.of("name", "새 이름"))));
        assertThatThrownBy(() -> eventService.patch(operator.getPublicId(), created.eventId(),
                new EventPatchRequest(created.settingsVersion(), patch(Map.of("name", "옛 버전")))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EventErrorCode.SETTINGS_VERSION_CONFLICT);

        User stranger = userRepository.save(User.localUser("s@harucut.com", "e", "s"));
        assertThatThrownBy(() -> eventService.get(stranger.getPublicId(), created.eventId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("게시한 행사가 쓰는 프레임은 지울 수 없다(FRAME-006)")
    void frameUsedByEventCannotBeDeleted() {
        FrameResponse frame = orgFrame(FrameType.CLASSIC);
        FrameVersionResponse version = frameVersionService.publish(operator.getPublicId(), frame.frameId());
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), settings("행사", "uses-frame"));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(), patch(Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", version.frameVersionId(), "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        eventService.publish(operator.getPublicId(), created.eventId());

        assertThatThrownBy(() -> frameService.deleteFrame(operator.getPublicId(), frame.frameId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.VERSION_IN_USE);
    }

    @Test
    @DisplayName("상시 캠페인: 종료 시각이 있으면 게시 위반(captureEndsAt), 비우면 게시된다 — 게시 뒤 종류 변경은 GEN-002, 복제는 종류를 가져가고, 출처는 EVENT")
    void alwaysOnCampaign() {
        Long frameVersionId = publishedFrame(FrameType.CLASSIC);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), new EventSettings("상시", "always-1",
                null, java.time.OffsetDateTime.now().plusDays(30), null, null, null, null, null, null, null, null, null, EventKind.ALWAYS_ON));
        assertThat(created.settings().kind()).isEqualTo(EventKind.ALWAYS_ON);
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(), patch(Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));

        assertThatThrownBy(() -> eventService.publish(operator.getPublicId(), created.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field")).containsExactly("captureEndsAt");
                });
        ObjectNode clearEnd = objectMapper.createObjectNode().putNull("captureEndsAt");
        eventService.patch(operator.getPublicId(), created.eventId(),
                new EventPatchRequest(eventService.get(operator.getPublicId(), created.eventId()).settingsVersion(), clearEnd));
        EventResponse live = eventService.publish(operator.getPublicId(), created.eventId());
        assertThat(live.status()).isEqualTo(EventStatus.LIVE);
        assertThat(live.captureOpen()).isTrue();

        assertThatThrownBy(() -> eventService.patch(operator.getPublicId(), created.eventId(),
                new EventPatchRequest(live.settingsVersion(), patch(Map.of("kind", "SCHEDULED")))))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
        assertThat(eventService.cloneEvent(operator.getPublicId(), created.eventId(), "상시 복제", "always-2").settings().kind())
                .isEqualTo(EventKind.ALWAYS_ON);
        assertThat(eventService.effectiveSettings(operator.getPublicId(), created.eventId()).sources()).containsEntry("kind", "EVENT");
        // 안 보내면 SCHEDULED
        assertThat(eventService.create(operator.getPublicId(), org.workspaceId(), settings("기본", "scheduled-1")).settings().kind())
                .isEqualTo(EventKind.SCHEDULED);
    }

    private Long publishedFrame(FrameType type) {
        return frameVersionService.publish(operator.getPublicId(), orgFrame(type).frameId()).frameVersionId();
    }

    // 행사 공간에 만든 프레임(이미지 없음 — 편입할 자산이 없어 S3 를 부르지 않는다)
    private FrameResponse orgFrame(FrameType type) {
        return frameService.createFrame(operator.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", type, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId()));
    }

    private static EventSettings settings(String name, String slug) {
        return new EventSettings(name, slug, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ObjectNode patch(Map<String, Object> fields) {
        return objectMapper.valueToTree(fields);
    }
}
