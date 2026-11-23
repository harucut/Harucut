package com.harucut.workspace;

import com.harucut.audit.repository.AuditLogRepository;
import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceKind;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.enums.WorkspaceType;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 공간 종류(업체/주최자, docs/decisions.md 2026-11-03). ENTERPRISE 는 멤버가 사진을 보지 않고 고객만 본다. HOST 는 지금 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("공간 종류(업체/주최자) — 통합")
class WorkspaceKindIntegrationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ProjectService projectService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;

    private User admin;
    private User owner;
    private String workspaceId;
    private Long workspaceInternalId;
    private Cookie adminCookie;
    private Cookie ownerCookie;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        admin = userRepository.save(User.localUser("admin@harucut.com", "e", "플랫폼 관리자"));
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "대표"));
        entityManager.flush();
        jdbcTemplate.update("update users set user_role = 'ROLE_ADMIN' where user_id = ?", admin.getId());
        entityManager.clear();
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "○○웨딩홀");
        workspaceId = org.workspaceId();
        workspaceInternalId = workspaceRepository.findByPublicId(workspaceId).orElseThrow().getId();
        adminCookie = cookie(admin, UserRole.ROLE_ADMIN);
        ownerCookie = cookie(owner, UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("ENTERPRISE — 행사를 만든 OWNER 는 사진을 못 보고(PHOTO-001), 고객 초대·사용량·찍은 수는 되고, 멤버의 수락·권한 부여는 MEMBER-005")
    void enterprise() {
        assertThat(data(patchKind(workspaceId, "ENTERPRISE", adminCookie), 200).path("kind").asString()).isEqualTo("ENTERPRISE");
        assertThat(workspaceService.listMine(owner.getPublicId())).filteredOn(w -> w.workspaceId().equals(workspaceId))
                .singleElement().extracting(WorkspaceResponse::kind).isEqualTo(WorkspaceKind.ENTERPRISE);

        EventResponse event = eventService.create(owner.getPublicId(), workspaceId, settings("민지 ♥ 준호"));
        assertThat(eventGrantService.grants(owner.getPublicId(), event.eventId())).isEmpty();
        assertCode(mvc.get().uri("/api/auth/events/{id}/photos", event.eventId()).cookie(ownerCookie).exchange(), 403, "PHOTO-001");
        assertThat(mvc.get().uri("/api/auth/events/{id}/usage", event.eventId()).cookie(ownerCookie).exchange()).hasStatus(200);
        MemberDtos.MyEvent mine = eventGrantService.myEvents(owner.getPublicId()).stream()
                .filter(e -> e.eventId().equals(event.eventId())).findFirst().orElseThrow();
        assertThat(mine.access().permissions()).isEmpty();
        assertThat(mine.access().operations()).contains(OperationPermission.MANAGE_EVENT, OperationPermission.INVITE_CUSTOMERS);
        assertThat(mine.completedPhotoCount()).isZero();

        // 고객 초대는 그대로. 자기 수락은 MEMBER-005 이고 토큰은 남아 고객이 받는다
        MemberDtos.InvitationIssued issued = eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(PhotoPermission.all(), "신랑신부"));
        assertCode(() -> invitationService.accept(owner.getPublicId(), issued.inviteToken()), MemberErrorCode.ENTERPRISE_MEMBER_NO_PHOTOS);
        User couple = userRepository.save(User.localUser("couple@harucut.com", "e", "신랑신부"));
        assertThat(invitationService.accept(couple.getPublicId(), issued.inviteToken()).permissions())
                .containsExactlyInAnyOrderElementsOf(PhotoPermission.all());
        assertThat(mvc.get().uri("/api/auth/events/{id}/photos", event.eventId()).cookie(cookie(couple, UserRole.ROLE_USER)).exchange())
                .hasStatus(200);
        Long coupleGrant = eventGrantService.grants(owner.getPublicId(), event.eventId()).getFirst().grantId();
        assertThat(eventGrantService.update(owner.getPublicId(), event.eventId(), coupleGrant, Set.of(PhotoPermission.VIEW_PHOTOS))
                .permissions()).containsExactly(PhotoPermission.VIEW_PHOTOS);

        // ADMIN 멤버도 고객 초대를 수락하지 못한다. 권한 행이 있어도 판정에서 빠진다
        User staff = userRepository.save(User.localUser("staff@harucut.com", "e", "직원"));
        membershipRepository.save(WorkspaceMembership.member(workspaceInternalId, staff.getId(), WorkspaceRole.ADMIN));
        MemberDtos.InvitationIssued second = eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null));
        assertCode(() -> invitationService.accept(staff.getPublicId(), second.inviteToken()), MemberErrorCode.ENTERPRISE_MEMBER_NO_PHOTOS);
        assertCode(mvc.get().uri("/api/auth/events/{id}/photos", event.eventId()).cookie(cookie(staff, UserRole.ROLE_USER)).exchange(),
                403, "PHOTO-001");

        // 프로젝트 멤버(OPERATOR)에게 사진 권한을 주면 MEMBER-005, 운영 권한만은 된다
        User operator = userRepository.save(User.localUser("op@harucut.com", "e", "담당자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceInternalId, operator.getId(), WorkspaceRole.OPERATOR));
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), workspaceId, new ProjectDtos.CreateRequest("봄 시즌", null));
        assertCode(() -> projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(OperationPermission.MANAGE_EVENT), Set.of(PhotoPermission.VIEW_PHOTOS))),
                MemberErrorCode.ENTERPRISE_MEMBER_NO_PHOTOS);
        assertThat(projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(OperationPermission.MANAGE_EVENT), Set.of())).operations())
                .containsExactly(OperationPermission.MANAGE_EVENT);

        // 복제도 만든 사람에게 권한을 주지 않는다
        EventResponse copy = eventService.cloneEvent(owner.getPublicId(), event.eventId(), "복제", "wk-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(eventGrantService.grants(owner.getPublicId(), copy.eventId())).isEmpty();

        // HOST 로 되돌리면 새 행사는 만든 사람이 권한을 받는다
        data(patchKind(workspaceId, "HOST", adminCookie), 200);
        EventResponse later = eventService.create(owner.getPublicId(), workspaceId, settings("다음 예식"));
        assertThat(eventGrantService.grants(owner.getPublicId(), later.eventId())).singleElement()
                .satisfies(grant -> assertThat(grant.permissions()).containsExactlyInAnyOrderElementsOf(PhotoPermission.all()));
    }

    @Test
    @DisplayName("HOST(기본) — 행사를 만든 OWNER 가 사진 권한 네 개를 받고, 멤버의 고객 초대 수락도 된다(지금 그대로)")
    void host() {
        assertThat(workspaceService.listMine(owner.getPublicId())).filteredOn(w -> w.workspaceId().equals(workspaceId))
                .singleElement().extracting(WorkspaceResponse::kind).isEqualTo(WorkspaceKind.HOST);
        EventResponse event = eventService.create(owner.getPublicId(), workspaceId, settings("파티"));
        assertThat(eventGrantService.grants(owner.getPublicId(), event.eventId())).singleElement().satisfies(grant -> {
            assertThat(grant.userId()).isEqualTo(owner.getPublicId());
            assertThat(grant.permissions()).containsExactlyInAnyOrderElementsOf(PhotoPermission.all());
        });
        assertThat(mvc.get().uri("/api/auth/events/{id}/photos", event.eventId()).cookie(ownerCookie).exchange()).hasStatus(200);

        User staff = userRepository.save(User.localUser("staff@harucut.com", "e", "직원"));
        membershipRepository.save(WorkspaceMembership.member(workspaceInternalId, staff.getId(), WorkspaceRole.ADMIN));
        MemberDtos.InvitationIssued issued = eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null));
        assertThat(invitationService.accept(staff.getPublicId(), issued.inviteToken()).permissions()).containsExactly(PhotoPermission.VIEW_PHOTOS);
    }

    @Test
    @DisplayName("관리자만 바꾼다 — 개인 공간은 404, 일반 사용자는 403, 같은 값이면 감사 로그가 늘지 않는다, kind 가 빠지면 400")
    void adminGuards() {
        String personal = workspaceService.listMine(owner.getPublicId()).stream()
                .filter(w -> w.type() == WorkspaceType.PERSONAL).findFirst().orElseThrow().workspaceId();
        assertCode(patchKind(personal, "ENTERPRISE", adminCookie), 404, "GEN-031");
        assertCode(patchKind(workspaceId, "ENTERPRISE", ownerCookie), 403, "GEN-021");
        assertCode(mvc.patch().uri("/api/admin/workspaces/{id}/kind", workspaceId).cookie(adminCookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"계약\"}").exchange(), 400, "GEN-003");

        data(patchKind(workspaceId, "ENTERPRISE", adminCookie), 200);
        data(patchKind(workspaceId, "ENTERPRISE", adminCookie), 200);
        assertThat(auditLogRepository.findAll()).filteredOn(log -> "WORKSPACE_KIND_CHANGED".equals(log.getAction())).hasSize(1);
        assertThat(workspaceRepository.findByPublicId(workspaceId).orElseThrow().isEnterprise()).isTrue();
    }

    // ── helpers ──────────────────────────────

    private MvcTestResult patchKind(String workspace, String kind, Cookie cookie) {
        return mvc.patch().uri("/api/admin/workspaces/{id}/kind", workspace).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"" + kind + "\",\"reason\":\"○○웨딩홀 계약\"}").exchange();
    }

    private static EventSettings settings(String name) {
        return new EventSettings(name, "wk-" + UUID.randomUUID().toString().substring(0, 8),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Cookie cookie(User user, UserRole role) {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService.createAccessToken(user.getPublicId(), role, UserStatus.ACTIVE).value());
    }

    private JsonNode data(MvcTestResult result, int status) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        return body.path("data");
    }

    private void assertCode(MvcTestResult result, int status, String code) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
