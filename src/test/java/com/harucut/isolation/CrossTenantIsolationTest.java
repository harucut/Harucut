package com.harucut.isolation;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.draft.dto.DraftCreateRequest;
import com.harucut.draft.dto.DraftResponse;
import com.harucut.draft.service.FrameDraftService;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.service.ExportService;
import com.harucut.export.service.MultiExportService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.MembershipService;
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
import org.springframework.http.MediaType;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P3-6 격리 검수 (R5 21절, ADR-0009 §7-2) — 고객 A 의 OWNER 가 고객 B 의 자원 id 로 회원 API 를 부른다.
 * 대상은 손으로 고르지 않고 계약(openapi.generated.json)에서 읽는다 — 새 엔드포인트도 저절로 검사에 들어오고,
 * 처음 보는 경로 변수나 본문 견본이 없는 엔드포인트는 실패해서 빠뜨리지 못하게 한다.
 * 본문은 B 의 자원 기준으로 유효하게 만든다(버전·revision 까지) — 거절 사유가 권한 말고는 없게.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("격리 — 다고객 교차 접근(계약 전수)")
class CrossTenantIsolationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    // B 만 아는 값 — 거절 응답에도 나오면 안 된다
    private static final List<String> B_SECRETS = List.of("B사비밀공간", "B사비밀행사", "B사비밀프로젝트", "victim@b.com", "B사비밀프레임");

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private MembershipService membershipService;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private ProjectService projectService;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private EventGrantRepository grantRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private ExportService exportService;
    @Autowired private MultiExportService multiExportService;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameDraftService frameDraftService;
    @Autowired private UserMediaRepository userMediaRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private User intruder;
    private User victim;
    private String intruderWorkspace;
    private EventResponse intruderEvent;
    private String intruderProject;
    private Long victimOrgFrameVersionId;
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final Map<String, String> bodies = new LinkedHashMap<>();

    @BeforeEach
    void twoCustomers() {
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/zip");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(100, "image/png")));

        // 고객 A — 자기 공간에서는 모든 권한이 있는 OWNER(이용권 포함)
        intruder = userRepository.save(User.localUser("intruder@a.com", "e", "A사 오너"));
        WorkspaceResponse a = workspaceService.createOrganization(intruder.getPublicId(), "A사");
        intruderWorkspace = a.workspaceId();
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(a.workspaceId()).orElseThrow().getId());
        intruderEvent = eventService.create(intruder.getPublicId(), a.workspaceId(),
                new EventSettings("A사 행사", "a-event", null, null, null, null, null, null, null, null, null, null, null, null));
        intruderProject = projectService.create(intruder.getPublicId(), a.workspaceId(), new ProjectDtos.CreateRequest("A사 프로젝트", null)).projectId();

        // 고객 B — 공간·프로젝트·행사·멤버·권한·초대·부스·사진·묶음, 그리고 개인 자원(프레임·초안·미디어·합성·자산)
        victim = userRepository.save(User.localUser("victim@b.com", "e", "B사 오너"));
        WorkspaceResponse b = workspaceService.createOrganization(victim.getPublicId(), "B사비밀공간");
        Long bWorkspaceId = workspaceRepository.findByPublicId(b.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, bWorkspaceId);
        ProjectDtos.Project project = projectService.create(victim.getPublicId(), b.workspaceId(),
                new ProjectDtos.CreateRequest("B사비밀프로젝트", null));
        EventResponse created = eventService.create(victim.getPublicId(), b.workspaceId(),
                new EventSettings("B사비밀행사", "b-event", null, null, null, null, null, null, null, null, null, null,
                        project.projectId(), null));
        Event event = eventRepository.findByPublicId(created.eventId()).orElseThrow();
        User member = userRepository.save(User.localUser("member@b.com", "e", "B사 운영"));
        membershipRepository.save(WorkspaceMembership.member(bWorkspaceId, member.getId(), WorkspaceRole.OPERATOR));
        eventGrantService.assignOperator(victim.getPublicId(), created.eventId(), member.getPublicId(),
                EnumSet.of(OperationPermission.MANAGE_EVENT));
        projectService.putMember(victim.getPublicId(), project.projectId(), member.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(), EnumSet.of(PhotoPermission.VIEW_PHOTOS)));
        ComposeJob photo = donePhoto(event);
        entityManager.flush();
        entityManager.clear();

        Long frameId = frameService.createFrame(victim.getPublicId(), frame(victim, null)).frameId();
        victimOrgFrameVersionId = frameVersionService.publish(victim.getPublicId(),
                frameService.createFrame(victim.getPublicId(), frame(victim, b.workspaceId())).frameId()).frameVersionId();
        int versionNo = frameVersionService.publish(victim.getPublicId(), frameId).versionNo();
        DraftResponse draft = frameDraftService.create(victim.getPublicId(),
                new DraftCreateRequest(null, null, objectMapper.valueToTree(Map.of("title", "B사비밀프레임", "layers", List.of())), null));
        UserMedia media = userMediaRepository.save(UserMedia.of(victim, S3Keys.userRoot(victim.getPublicId()) + "media/1.jpg", "B사비밀프레임"));
        ComposeJob personalJob = composeJobRepository.save(ComposeJob.create(victim, frameId, "b-personal", List.of("a", "b", "c", "d"), spec()));
        Asset asset = assetRepository.save(Asset.pendingUpload(Asset.newPublicId(), workspaceService.personalWorkspaceOf(victim.getId()).getId(),
                AssetPurpose.FRAME_ASSET, S3Keys.userRoot(victim.getPublicId()) + "assets/1.png", "image/png", 100, victim.getId()));

        ids.put("workspaceId", b.workspaceId());
        ids.put("projectId", project.projectId());
        ids.put("eventId", created.eventId());
        ids.put("events/exportId", exportService.create(victim.getPublicId(), created.eventId(), new ExportDtos.ExportRequest(null, null)).exportId());
        ids.put("projects/exportId", multiExportService.create(victim.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(created.eventId()))).exportId());
        ids.put("workspaces/exportId", multiExportService.create(victim.getPublicId(), ExportScope.WORKSPACE, b.workspaceId(),
                new ExportDtos.MultiExportRequest(List.of(created.eventId()))).exportId());
        ids.put("grantId", grantRepository.findByEventIdAndUserId(event.getId(), member.getId()).orElseThrow().getId().toString());
        ids.put("userId", member.getPublicId());
        ids.put("photoId", photo.getId().toString());
        ids.put("deviceId", boothDeviceService.create(victim.getPublicId(), created.eventId(), "B사 부스").deviceId());
        ids.put("invitationId", membershipService.invite(victim.getPublicId(), b.workspaceId(),
                new MemberDtos.MemberInvitationRequest(WorkspaceRole.DESIGNER, null)).invitationId());
        ids.put("assetId", asset.getPublicId());
        ids.put("draftId", draft.draftId());
        ids.put("frameId", frameId.toString());
        ids.put("versionNo", String.valueOf(versionNo));
        ids.put("jobId", personalJob.getId().toString());
        ids.put("mediaId", media.getId().toString());

        int settingsVersion = eventService.get(victim.getPublicId(), created.eventId()).settingsVersion();
        String eventIds = "{\"eventIds\":[\"" + created.eventId() + "\"]}";
        bodies.put("PATCH /api/auth/events/{eventId}", "{\"settingsVersion\":" + settingsVersion + ",\"settings\":{\"name\":\"침입\"}}");
        bodies.put("POST /api/auth/events/{eventId}/booth-devices", "{\"name\":\"침입\"}");
        bodies.put("POST /api/auth/events/{eventId}/clone", "{\"name\":\"침입\",\"slug\":\"intrude-clone\"}");
        bodies.put("POST /api/auth/events/{eventId}/exports", "{}");
        bodies.put("PATCH /api/auth/events/{eventId}/grants/{grantId}", "{\"permissions\":[\"VIEW_PHOTOS\"]}");
        bodies.put("POST /api/auth/events/{eventId}/invitations", "{\"permissions\":[\"VIEW_PHOTOS\"]}");
        bodies.put("PUT /api/auth/events/{eventId}/operators/{userId}", "{\"operations\":[\"MANAGE_EVENT\"]}");
        bodies.put("PATCH /api/auth/projects/{projectId}", "{\"name\":\"침입\"}");
        bodies.put("POST /api/auth/projects/{projectId}/exports", eventIds);
        bodies.put("PUT /api/auth/projects/{projectId}/members/{userId}", "{\"operations\":[],\"permissions\":[\"VIEW_PHOTOS\"]}");
        bodies.put("PUT /api/auth/user/frame-drafts/{draftId}", "{\"revision\":" + draft.revision() + ",\"document\":{\"title\":\"침입\",\"layers\":[]}}");
        bodies.put("PUT /api/auth/user/frame/{frameId}", json(frame(victim, null)));
        bodies.put("PATCH /api/auth/user/media/{mediaId}/display-name", "{\"displayName\":\"침입\"}");
        bodies.put("PATCH /api/auth/workspaces/{workspaceId}/brand-defaults", "{}");
        bodies.put("POST /api/auth/workspaces/{workspaceId}/events", "{\"name\":\"침입\",\"slug\":\"intrude-event\"}");
        bodies.put("POST /api/auth/workspaces/{workspaceId}/exports", eventIds);
        bodies.put("POST /api/auth/workspaces/{workspaceId}/invitations", "{\"role\":\"DESIGNER\"}");
        bodies.put("PATCH /api/auth/workspaces/{workspaceId}/members/{userId}", "{\"role\":\"DESIGNER\"}");
        bodies.put("POST /api/auth/workspaces/{workspaceId}/projects", "{\"name\":\"침입\"}");
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("회원 API 중 경로에 자원 id 가 있는 것 전부: 남의 공간·프로젝트·행사·묶음·개인 자원은 404 — 2xx·403(존재 노출)·B 의 이름 노출 없음")
    void foreignIdsOnEveryAuthEndpoint() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Endpoint endpoint : contract("/api/auth/")) {
            if (!endpoint.template().contains("{")) {
                continue;
            }
            String uri = resolve(endpoint);
            String body = bodies.get(endpoint.key());
            if (endpoint.hasBody() && body == null) {
                violations.add(endpoint.key() + " — 본문 견본이 없다(이 테스트의 bodies 에 B 기준 유효 본문을 더할 것)");
                continue;
            }
            MvcTestResult result = call(endpoint.method(), uri, body);
            checked++;
            int status = result.getResponse().getStatus();
            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (status != 404) {
                violations.add(endpoint.key() + " → " + status + " " + abbreviate(response));
            }
            B_SECRETS.stream().filter(response::contains)
                    .forEach(secret -> violations.add(endpoint.key() + " — 응답에 B 의 값 노출: " + secret));
        }
        assertThat(checked).isGreaterThan(60);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("본문에 B 의 id·S3 key 를 넣어 A 의 자원에 엮기: 행사→B 프로젝트(B 프레임 발행본은 게시에서), 프레임·초안·자산→B 공간, 초안→B 프레임·자산, 합성→B 프레임·B 원본 key, 묶음→B 행사, 멤버→B 사용자 — 모두 거절")
    void foreignIdsInBodies() {
        String victimRoot = S3Keys.userRoot(victim.getPublicId());
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("행사 만들기 — B 프로젝트에", new String[]{"POST", "/api/auth/workspaces/" + intruderWorkspace + "/events",
                json(Map.of("name", "침입", "slug", "intrude-a", "projectId", ids.get("projectId")))});
        cases.put("행사 수정 — B 프로젝트로 옮기기", new String[]{"PATCH", "/api/auth/events/" + intruderEvent.eventId(),
                json(Map.of("settingsVersion", intruderEvent.settingsVersion(), "settings", Map.of("projectId", ids.get("projectId"))))});
        cases.put("프레임 만들기 — B 공간에", new String[]{"POST", "/api/auth/user/frame",
                json(frame(intruder, ids.get("workspaceId")))});
        cases.put("프레임 만들기 — 미리보기 key 가 B 의 폴더", new String[]{"POST", "/api/auth/user/frame",
                json(new FrameCreateRequest("침입", null, victimRoot + "frames/p.png", FrameType.CLASSIC, null, null,
                        new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, null))});
        cases.put("초안 만들기 — B 공간에", new String[]{"POST", "/api/auth/user/frame-drafts",
                json(Map.of("workspaceId", ids.get("workspaceId"), "document", Map.of("title", "침입", "layers", List.of())))});
        cases.put("초안 만들기 — B 의 프레임", new String[]{"POST", "/api/auth/user/frame-drafts",
                json(Map.of("frameId", Long.valueOf(ids.get("frameId")), "document", Map.of("title", "침입", "layers", List.of())))});
        cases.put("초안 만들기 — B 의 자산", new String[]{"POST", "/api/auth/user/frame-drafts",
                json(Map.of("assetIds", List.of(ids.get("assetId")), "document", Map.of("title", "침입", "layers", List.of())))});
        cases.put("자산 올리기 — B 공간에", new String[]{"POST", "/api/auth/assets/upload-intents",
                json(Map.of("workspaceId", ids.get("workspaceId"), "purpose", "FRAME_ASSET", "contentType", "PNG",
                        "fileSize", 100, "filename", "x.png"))});
        cases.put("합성 — B 의 프레임", new String[]{"POST", "/api/auth/user/media/compose",
                json(Map.of("frameId", Long.valueOf(ids.get("frameId")), "idempotencyKey", "intrude-1",
                        "sourceKeys", List.of(1, 2, 3, 4).stream().map(i -> S3Keys.userRoot(intruder.getPublicId()) + "fourcuts/sources/" + i + ".png").toList()))});
        cases.put("묶음 — A 프로젝트에 B 행사", new String[]{"POST", "/api/auth/projects/" + intruderProject + "/exports",
                json(Map.of("eventIds", List.of(ids.get("eventId"))))});
        cases.put("묶음 — A 공간에 B 행사", new String[]{"POST", "/api/auth/workspaces/" + intruderWorkspace + "/exports",
                json(Map.of("eventIds", List.of(ids.get("eventId"))))});
        cases.put("프로젝트 멤버 — B 의 사용자", new String[]{"PUT", "/api/auth/projects/" + intruderProject + "/members/" + ids.get("userId"),
                json(Map.of("operations", List.of(), "permissions", List.of("VIEW_PHOTOS")))});
        cases.put("공간 멤버 역할 — B 의 사용자", new String[]{"PATCH", "/api/auth/workspaces/" + intruderWorkspace + "/members/" + ids.get("userId"),
                json(Map.of("role", "DESIGNER"))});
        cases.put("행사 운영자 — B 의 사용자", new String[]{"PUT", "/api/auth/events/" + intruderEvent.eventId() + "/operators/" + ids.get("userId"),
                json(Map.of("operations", List.of("MANAGE_EVENT")))});

        List<String> accepted = new ArrayList<>();
        cases.forEach((name, c) -> {
            MvcTestResult result = call(c[0], c[1], c[2]);
            int status = result.getResponse().getStatus();
            if (status < 400 || status >= 500) {
                accepted.add(name + " → " + status + " " + abbreviate(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)));
            }
        });
        assertThat(accepted).isEmpty();

        // 행사 설정의 프레임 발행본은 임시 저장에선 id 만 보관하고(내용을 풀지 않는다) 게시에서 공간을 본다 — B 의 발행본은 게시 400 EVENT-003
        MvcTestResult draft = call("PATCH", "/api/auth/events/" + intruderEvent.eventId(), json(Map.of("settingsVersion",
                intruderEvent.settingsVersion(), "settings", Map.of("allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", victimOrgFrameVersionId, "layoutType", "CLASSIC", "isDefault", true))))));
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        assertThat(new String(draft.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).doesNotContain("B사비밀프레임");
        MvcTestResult publish = call("POST", "/api/auth/events/" + intruderEvent.eventId() + "/publish", null);
        assertThat(publish.getResponse().getStatus()).isEqualTo(400);
        assertThat(new String(publish.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("EVENT-003", "이 공간의 프레임이나 시스템 프레임만 쓸 수 있다");

        // 합성 원본이 B 의 폴더면 — 본인 프레임이어도 거절
        Long ownFrame = frameService.createFrame(intruder.getPublicId(), frame(intruder, null)).frameId();
        assertThat(call("POST", "/api/auth/user/media/compose", json(Map.of("frameId", ownFrame, "idempotencyKey", "intrude-2",
                "sourceKeys", List.of(1, 2, 3, 4).stream().map(i -> victimRoot + "fourcuts/sources/" + i + ".png").toList())))
                .getResponse().getStatus()).isBetween(400, 499);
        // 프레임 필터로 B 의 초안을 찾을 수 없다
        assertThat(new String(call("GET", "/api/auth/user/frame-drafts?frameId=" + ids.get("frameId"), null).getResponse()
                .getContentAsByteArray(), StandardCharsets.UTF_8)).doesNotContain(ids.get("draftId"), "B사비밀프레임");
    }

    // B 의 id 를 경로에 넣는다. 처음 보는 경로 변수면 실패 — 이 테스트에 그 자원의 B 쪽 id 를 더해야 한다
    private String resolve(Endpoint endpoint) {
        String template = endpoint.template();
        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder uri = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = name.equals("exportId") ? ids.get(template.split("/")[3] + "/exportId") : ids.get(name);
            if (value == null) {
                throw new AssertionError(template + " — 처음 보는 경로 변수 {" + name + "}: B 쪽 id 를 ids 에 더할 것");
            }
            matcher.appendReplacement(uri, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(uri);
        // 필수 쿼리 변수도 B 의 값으로
        for (String name : endpoint.requiredQuery()) {
            String value = ids.get(name);
            if (value == null) {
                throw new AssertionError(template + " — 처음 보는 필수 쿼리 변수 " + name + ": B 쪽 값을 ids 에 더할 것");
            }
            uri.append(uri.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(value);
        }
        return uri.toString();
    }

    private static List<String> requiredQuery(JsonNode operation) {
        List<String> names = new ArrayList<>();
        for (JsonNode parameter : operation.path("parameters")) {
            if ("query".equals(parameter.path("in").asString()) && parameter.path("required").asBoolean()) {
                names.add(parameter.path("name").asString());
            }
        }
        return names;
    }

    private MvcTestResult call(String method, String uri, String body) {
        var request = mvc.method(HttpMethod.valueOf(method.toUpperCase())).uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(intruder));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private String token(User user) {
        return jwtTokenService.createAccessToken(user.getPublicId(), user.getUserRole(), user.getUserStatus()).value();
    }

    record Endpoint(String method, String template, boolean hasBody, List<String> requiredQuery) {
        String key() {
            return method.toUpperCase() + " " + template;
        }
    }

    private List<Endpoint> contract(String prefix) throws IOException {
        JsonNode paths = objectMapper.readTree(Files.readString(Path.of("docs/api/openapi.generated.json"))).path("paths");
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (!path.getKey().startsWith(prefix)) {
                continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (METHODS.contains(operation.getKey())) {
                    endpoints.add(new Endpoint(operation.getKey(), path.getKey(), operation.getValue().has("requestBody"),
                            requiredQuery(operation.getValue())));
                }
            }
        }
        assertThat(endpoints).isNotEmpty();
        return endpoints;
    }

    private ComposeJob donePhoto(Event event) {
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob job = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/b/", 1L, "b-photo",
                List.of("a", "b", "c", "d"), spec(), event.getId(), null, null, 41L));
        composeJobRepository.markDone(job.getId(), "uploads/events/b/results/1.jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        return job;
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"), FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false), List.of());
    }

    private static FrameCreateRequest frame(User owner, String workspaceId) {
        return new FrameCreateRequest("B사비밀프레임", null, S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, workspaceId);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static String abbreviate(String response) {
        return response.length() > 160 ? response.substring(0, 160) + "…" : response;
    }
}
