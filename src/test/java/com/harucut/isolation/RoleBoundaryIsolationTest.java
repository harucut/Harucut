package com.harucut.isolation;

import com.harucut.auth.service.JwtTokenService;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P3-6 격리 검수 (R5 21절, ADR-0009 §7-2) — 같은 공간 안의 역할 경계. 실제 보안 필터·컨트롤러로 본다.
 * 공간 W: 프로젝트 P1(행사 E1)·P2(행사 E2), 프로젝트 없는 행사 E3.
 * - 운영 담당 O: P1 멤버(MANAGE_EVENT·VIEW_PHOTOS)만 — E2·E3 는 없는 것과 같다(404), 멤버·감사 로그는 403
 * - 행사 고객 C: E1 초대 수락(VIEW_PHOTOS) — 공간 멤버가 아니다. 공간·다른 행사의 이름도 보지 못한다
 * - 플랫폼 관리자가 아닌 사용자: /api/admin 전부 403(계약 전수)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("격리 — 같은 공간 안의 역할 경계")
class RoleBoundaryIsolationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private ProjectService projectService;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private User operator;
    private User customer;
    private String workspace;
    private String p1;
    private String p2;
    private String e1;
    private String e2;
    private String e3;
    private Long photo1;

    @BeforeEach
    void oneWorkspace() {
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get");

        owner = userRepository.save(User.localUser("owner@w.com", "e", "오너"));
        WorkspaceResponse w = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀W");
        workspace = w.workspaceId();
        Long workspaceId = workspaceRepository.findByPublicId(workspace).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        p1 = projectService.create(owner.getPublicId(), workspace, new ProjectDtos.CreateRequest("봄시즌P1", null)).projectId();
        p2 = projectService.create(owner.getPublicId(), workspace, new ProjectDtos.CreateRequest("가을시즌P2", null)).projectId();
        e1 = event("행사E1", "role-e1", p1);
        e2 = event("행사E2", "role-e2", p2);
        e3 = event("행사E3", "role-e3", null);
        photo1 = donePhoto(e1);
        donePhoto(e2);

        operator = userRepository.save(User.localUser("op@w.com", "e", "운영 담당"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        projectService.putMember(owner.getPublicId(), p1, operator.getPublicId(),
                new ProjectDtos.MemberRequest(EnumSet.of(OperationPermission.MANAGE_EVENT), EnumSet.of(PhotoPermission.VIEW_PHOTOS)));

        customer = userRepository.save(User.localUser("bride@c.com", "e", "신부"));
        String invite = eventGrantService.inviteCustomer(owner.getPublicId(), e1,
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), "신부")).inviteToken();
        invitationService.accept(customer.getPublicId(), invite);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("운영 담당: 받은 프로젝트의 행사만 — 목록에도 그것만, 다른 행사·프로젝트는 404, 권한 밖 사진 삭제·멤버·감사 로그는 403")
    void operatorSeesOnlyGrantedProject() {
        String events = ok(operator, "GET", "/api/auth/workspaces/" + workspace + "/events");
        assertThat(events).contains(e1).doesNotContain(e2, e3, "행사E2", "행사E3");
        ok(operator, "GET", "/api/auth/events/" + e1);
        ok(operator, "GET", "/api/auth/events/" + e1 + "/photos");
        ok(operator, "GET", "/api/auth/projects/" + p1 + "/exports");

        for (String event : List.of(e2, e3)) {
            status(operator, "GET", "/api/auth/events/" + event, 404);
            status(operator, "GET", "/api/auth/events/" + event + "/photos", 404);
            status(operator, "GET", "/api/auth/events/" + event + "/effective-settings", 404);
        }
        status(operator, "GET", "/api/auth/projects/" + p2 + "/exports", 404);
        status(operator, "DELETE", "/api/auth/events/" + e1 + "/photos/" + photo1, 403);
        status(operator, "GET", "/api/auth/workspaces/" + workspace + "/members", 403);
        status(operator, "GET", "/api/auth/workspaces/" + workspace + "/audit-logs", 403);
        status(operator, "GET", "/api/auth/workspaces/" + workspace + "/exports", 403);
    }

    @Test
    @DisplayName("행사 고객: 내 행사 목록엔 그 행사 하나, 사진은 보기만 — 공간·다른 행사·프로젝트는 404, 이름도 응답에 없다")
    void customerSeesOnlyInvitedEvent() {
        String mine = ok(customer, "GET", "/api/auth/me/events");
        assertThat(mine).contains(e1).doesNotContain(e2, e3, "행사E2", "행사E3", "봄시즌P1", "가을시즌P2");
        ok(customer, "GET", "/api/auth/events/" + e1 + "/photos");
        status(customer, "DELETE", "/api/auth/events/" + e1 + "/photos/" + photo1, 403);
        status(customer, "POST", "/api/auth/events/" + e1 + "/exports", 403);
        // 운영은 못 한다 — 행사가 있다는 건 알지만(사진 권한) 운영 화면은 403
        status(customer, "GET", "/api/auth/events/" + e1, 403);

        for (String event : List.of(e2, e3)) {
            status(customer, "GET", "/api/auth/events/" + event + "/photos", 404);
            status(customer, "GET", "/api/auth/events/" + event, 404);
        }
        for (String path : List.of("/events", "/members", "/projects", "/frames", "/brand-defaults", "/audit-logs", "/exports")) {
            status(customer, "GET", "/api/auth/workspaces/" + workspace + path, 404);
        }
        status(customer, "GET", "/api/auth/projects/" + p1 + "/exports", 404);
        assertThat(ok(customer, "GET", "/api/auth/workspaces")).doesNotContain(workspace, "웨딩홀W");
    }

    @Test
    @DisplayName("플랫폼 관리자가 아니면 /api/admin 은 공간 OWNER 라도 전부 403 — 계약의 관리자 경로 전수")
    void adminPathsNeedPlatformAdmin() throws IOException {
        JsonNode paths = objectMapper.readTree(Files.readString(Path.of("docs/api/openapi.generated.json"))).path("paths");
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (!path.getKey().startsWith("/api/admin")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(operation.getKey())) {
                    continue;
                }
                String uri = path.getKey().replaceAll("\\{\\w+}", "1");
                MvcTestResult result = call(owner, operation.getKey().toUpperCase(), uri);
                checked++;
                if (result.getResponse().getStatus() != 403) {
                    violations.add(operation.getKey().toUpperCase() + " " + path.getKey() + " → " + result.getResponse().getStatus());
                }
            }
        }
        assertThat(checked).isGreaterThan(20);
        assertThat(violations).isEmpty();
    }

    private String event(String name, String slug, String projectId) {
        EventResponse created = eventService.create(owner.getPublicId(), workspace,
                new EventSettings(name, slug, null, null, null, null, null, null, null, null, null, null, projectId, null));
        return created.eventId();
    }

    private Long donePhoto(String eventPublicId) {
        Event event = eventRepository.findByPublicId(eventPublicId).orElseThrow();
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob job = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/w/", 1L,
                "w-" + eventPublicId, List.of("a", "b", "c", "d"),
                new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"), FrameType.CLASSIC.getLayout().slots(),
                        List.of(false, false, false, false), List.of()),
                event.getId(), null, null, 41L));
        composeJobRepository.markDone(job.getId(), "uploads/events/w/results/" + job.getId() + ".jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        return job.getId();
    }

    private String ok(User user, String method, String uri) {
        MvcTestResult result = call(user, method, uri);
        assertThat(result).as(method + " " + uri).hasStatus2xxSuccessful();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void status(User user, String method, String uri, int expected) {
        assertThat(call(user, method, uri)).as(method + " " + uri).hasStatus(expected);
    }

    private MvcTestResult call(User user, String method, String uri) {
        var request = mvc.method(HttpMethod.valueOf(method)).uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService
                        .createAccessToken(user.getPublicId(), user.getUserRole(), user.getUserStatus()).value());
        if (!method.equals("GET") && !method.equals("DELETE")) {
            request = request.contentType("application/json").content("{}");
        }
        return request.exchange();
    }
}
