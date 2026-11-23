package com.harucut.event.theme;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.support.TestEntitlements;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 디자이너(R5 L368, L837) — 테마와 제공 프레임만 고친다. 게시·기기·초대·사진은 자동으로 받지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("디자이너 테마 — 통합")
class DesignerThemeIntegrationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private PhotoService photoService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private User designer;
    private User viewer;
    private User operator;
    private User stranger;
    private WorkspaceResponse org;
    private EventResponse event;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString())).willAnswer(i -> "https://s3/" + i.getArgument(0));
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        designer = userRepository.save(User.localUser("designer@harucut.com", "e", "디자이너"));
        viewer = userRepository.save(User.localUser("viewer@harucut.com", "e", "뷰어"));
        operator = userRepository.save(User.localUser("operator@harucut.com", "e", "운영자"));
        stranger = userRepository.save(User.localUser("stranger@harucut.com", "e", "남"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        membershipRepository.save(WorkspaceMembership.member(workspaceId, designer.getId(), WorkspaceRole.DESIGNER));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, viewer.getId(), WorkspaceRole.VIEWER));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
        event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "designer-event", null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("설정을 읽고 theme·frames 를 고친다. theme: null 은 직접 값을 지운다")
    void editsThemeAndFrames() {
        assertThat(eventService.get(designer.getPublicId(), event.eventId()).eventId()).isEqualTo(event.eventId());
        assertThat(eventService.effectiveSettings(designer.getPublicId(), event.eventId())).isNotNull();

        EventResponse themed = patch(designer, event.settingsVersion(), Map.of(
                "theme", Map.of("colors", Map.of("accent", "#C8553D")),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true))));
        assertThat(themed.settings().theme().colors().accent()).isEqualTo("#C8553D");
        assertThat(themed.settings().frames()).hasSize(1);

        ObjectNode clear = objectMapper.createObjectNode();
        clear.putNull("theme");
        assertThat(eventService.patch(designer.getPublicId(), event.eventId(), new EventPatchRequest(themed.settingsVersion(), clear))
                .settings().theme()).isNull();
    }

    @Test
    @DisplayName("theme·frames 밖의 키가 하나라도 있으면 403 — 아무것도 저장하지 않는다. 게시·중지·초대·기기·사진도 막힌다")
    void nothingElse() {
        assertCode(() -> patch(designer, event.settingsVersion(), Map.of("name", "바꾼 이름")), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> patch(designer, event.settingsVersion(), Map.of("theme", Map.of("colors", Map.of("accent", "#000000")),
                "allowedLayouts", List.of("CLASSIC"))), GlobalErrorCode.FORBIDDEN);
        EventResponse untouched = eventService.get(owner.getPublicId(), event.eventId());
        assertThat(untouched.settingsVersion()).isEqualTo(event.settingsVersion());
        assertThat(untouched.settings().theme()).isNull();

        assertCode(() -> eventService.publish(designer.getPublicId(), event.eventId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventService.pause(designer.getPublicId(), event.eventId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventService.requireOperable(designer.getPublicId(), event.eventId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventGrantService.grants(designer.getPublicId(), event.eventId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventGrantService.inviteCustomer(designer.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> photoService.page(designer.getPublicId(), event.eventId(), null, null, null, null, null, null, null),
                PhotoErrorCode.PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("다른 역할: 뷰어·남은 행사를 못 본다(404), 다른 운영 권한만 있는 OPERATOR 는 고치지 못한다(403), EDIT_THEME 을 배정하면 테마만 고친다")
    void otherRoles() {
        assertCode(() -> eventService.get(viewer.getPublicId(), event.eventId()), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> patch(viewer, event.settingsVersion(), Map.of("theme", Map.of())), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> patch(stranger, event.settingsVersion(), Map.of("theme", Map.of())), GlobalErrorCode.NOT_FOUND);

        eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), operator.getPublicId(), Set.of(OperationPermission.MANAGE_BOOTH));
        assertCode(() -> patch(operator, event.settingsVersion(), Map.of("theme", Map.of())), GlobalErrorCode.FORBIDDEN);

        eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), operator.getPublicId(), Set.of(OperationPermission.EDIT_THEME));
        assertThat(patch(operator, event.settingsVersion(), Map.of("theme", Map.of("colors", Map.of("accent", "#000000"))))
                .settings().theme().colors().accent()).isEqualTo("#000000");
        assertCode(() -> patch(operator, event.settingsVersion() + 1, Map.of("name", "x")), GlobalErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("내 행사 목록: 디자이너 행은 EDIT_THEME 만·사진 수 없음, 고객 행은 역할 없음·사진 수 있음, 모두 공간 id 가 있다. 오너는 네 권한 전부")
    void myEvents() {
        MemberDtos.MyEvent row = eventGrantService.myEvents(designer.getPublicId()).get(0);
        assertThat(row.eventId()).isEqualTo(event.eventId());
        assertThat(row.workspaceId()).isEqualTo(org.workspaceId());
        assertThat(row.access().role()).isEqualTo(WorkspaceRole.DESIGNER);
        assertThat(row.access().operations()).containsExactly(OperationPermission.EDIT_THEME);
        assertThat(row.access().permissions()).isEmpty();
        assertThat(row.completedPhotoCount()).isNull();

        // 디자이너가 사진 권한을 따로 받으면 사진 수도 받는다
        invitationService.accept(designer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken());
        MemberDtos.MyEvent granted = eventGrantService.myEvents(designer.getPublicId()).get(0);
        assertThat(granted.access().permissions()).containsExactly(PhotoPermission.VIEW_PHOTOS);
        assertThat(granted.completedPhotoCount()).isZero();

        invitationService.accept(stranger.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken());
        MemberDtos.MyEvent customer = eventGrantService.myEvents(stranger.getPublicId()).get(0);
        assertThat(customer.workspaceId()).isEqualTo(org.workspaceId());
        assertThat(customer.access().role()).isNull();
        assertThat(customer.access().operations()).isEmpty();
        assertThat(customer.completedPhotoCount()).isZero();

        assertThat(eventGrantService.myEvents(owner.getPublicId()).get(0).access().operations())
                .containsExactlyInAnyOrder(OperationPermission.values());
        assertThat(eventGrantService.myEvents(viewer.getPublicId())).isEmpty();
    }

    @Test
    @DisplayName("행사 화면이 부르는 경로 — 설정·유효 설정·공간 프레임·고르기는 200, 사용량·프로젝트·공간 기본값은 403 GEN-021, 응답에 사진 수 키가 없다")
    void routesForDesignerScreen() {
        Cookie cookie = new Cookie(CookieManager.ACCESS_TOKEN,
                jwtTokenService.createAccessToken(designer.getPublicId(), UserRole.ROLE_USER, UserStatus.ACTIVE).value());

        assertThat(mvc.get().uri("/api/auth/events/{id}", event.eventId()).cookie(cookie)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/events/{id}/effective-settings", event.eventId()).cookie(cookie)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/workspaces/{id}/frames", org.workspaceId()).cookie(cookie)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/workspaces/{id}/frame-versions", org.workspaceId()).cookie(cookie)).hasStatusOk();
        assertForbidden(mvc.get().uri("/api/auth/events/{id}/usage", event.eventId()).cookie(cookie));
        assertForbidden(mvc.get().uri("/api/auth/workspaces/{id}/projects", org.workspaceId()).cookie(cookie));
        assertForbidden(mvc.get().uri("/api/auth/workspaces/{id}/brand-defaults", org.workspaceId()).cookie(cookie));
        assertThat(mvc.get().uri("/api/auth/me/events").cookie(cookie)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.data[0].access.operations[0]").isEqualTo("EDIT_THEME");
            assertThat(json).doesNotHavePath("$.data[0].completedPhotoCount");
            assertThat(json).doesNotHavePath("$.data[0].access.sources");
        });
    }

    private void assertForbidden(MockMvcTester.MockMvcRequestBuilder request) {
        assertThat(request).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");
    }

    private EventResponse patch(User user, int settingsVersion, Map<String, Object> settings) {
        return eventService.patch(user.getPublicId(), event.eventId(),
                new EventPatchRequest(settingsVersion, (ObjectNode) objectMapper.valueToTree(settings)));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
