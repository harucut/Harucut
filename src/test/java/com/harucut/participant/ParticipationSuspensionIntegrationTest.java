package com.harucut.participant;

import com.harucut.config.RateLimiter;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 공간 OWNER 가 전부 정지되면 그 공간의 행사는 새 참여를 받지 않는다(docs/decisions.md 2026-11-03).
 * 새 세션·회차·합성은 409 EVENT-006, 공개 조회·운영자 화면의 captureOpen 은 false, 이미 받은 결과 토큰은 그대로 열린다. 해제하면 바로 풀린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("정지 → 참여 중지 — 통합")
class ParticipationSuspensionIntegrationTest {

    private static final String PARTICIPANT = "X-Participant-Token";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User owner;
    private Long workspaceId;
    private String eventId;
    private String slug;
    private Long frameVersionId;
    private byte[] png;

    @BeforeEach
    void liveEvent() throws IOException {
        png = png();
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get-short");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        owner = userRepository.save(User.localUser("owner@harucut.com", "encoded", "주최자"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "웨딩팀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
        slug = "susp-" + UUID.randomUUID().toString().substring(0, 8);
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "byodEnabled", true,
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo", "version", 1,
                                "title", "사진 동의", "required", true, "body", "본문"))))));
        eventService.publish(owner.getPublicId(), created.eventId());
        eventId = created.eventId();
    }

    @Test
    @DisplayName("유일한 OWNER 가 정지되면 새 세션·회차·합성이 EVENT-006, captureOpen 은 false — 결과 토큰은 열리고, 해제하면 바로 풀린다")
    void suspendsWhileAllOwnersBlocked() {
        assertThat(publicEvent().path("captureOpen").asBoolean()).isTrue();
        String guest = session();
        String composedRun = startRun(guest);
        String resultToken = compose(guest, composedRun, uploadFour(guest, composedRun));
        // 정지 전에 입력까지 올려 둔 회차 — 합성 접수만 남았다
        String preparedRun = startRun(guest);
        List<String> prepared = uploadFour(guest, preparedRun);

        // 참가 흐름의 벌크 UPDATE(clearAutomatically)가 컨텍스트를 비웠다 — 위의 owner 는 분리됐으니 다시 읽어서 정지한다
        userRepository.findById(owner.getId()).orElseThrow().block(LocalDateTime.now());
        entityManager.flush();

        assertThat(publicEvent().path("captureOpen").asBoolean()).isFalse();
        assertCode(requestSession(), 409, "EVENT-006");
        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(PARTICIPANT, guest).exchange(),
                409, "EVENT-006");
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", guest, Map.of("captureRunId", preparedRun,
                "frameVersionId", frameVersionId, "sourceAssetIds", prepared, "idempotencyKey", "k-" + preparedRun)), 409, "EVENT-006");
        // 이미 찍은 사람의 결과 링크는 그대로 열린다
        assertThat(mvc.get().uri("/api/public/results").header("Authorization", "Bearer " + resultToken).exchange()).hasStatus(200);
        // 운영자 화면도 같은 값 — ADMIN 은 OWNER 가 아니라 공간을 열어 두지 못한다
        User admin = userRepository.save(User.localUser("admin@harucut.com", "encoded", "관리자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, admin.getId(), WorkspaceRole.ADMIN));
        assertThat(eventService.get(admin.getPublicId(), eventId).captureOpen()).isFalse();

        userRepository.findById(owner.getId()).orElseThrow().unblock();
        entityManager.flush();

        assertThat(publicEvent().path("captureOpen").asBoolean()).isTrue();
        assertThat(requestSession()).hasStatus(201);
        assertThat(eventService.get(admin.getPublicId(), eventId).captureOpen()).isTrue();
    }

    @Test
    @DisplayName("OWNER 가 둘이면 하나가 정지돼도 참여는 계속된다")
    void keepsRunningWhileAnotherOwnerIsActive() {
        User second = userRepository.save(User.localUser("second@harucut.com", "encoded", "공동 소유자"));
        membershipRepository.save(WorkspaceMembership.owner(workspaceId, second.getId()));

        owner.block(LocalDateTime.now());
        entityManager.flush();

        assertThat(publicEvent().path("captureOpen").asBoolean()).isTrue();
        assertThat(requestSession()).hasStatus(201);
        assertThat(eventService.get(second.getPublicId(), eventId).captureOpen()).isTrue();
    }

    // ── helpers ──────────────────────────────

    private JsonNode publicEvent() {
        return data(mvc.get().uri("/api/public/events/{slug}", slug).exchange(), 200);
    }

    private MvcTestResult requestSession() {
        return mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange();
    }

    private String session() {
        String guest = data(requestSession(), 201)
                .path("participantToken").asString();
        data(post("/api/participant/events/" + eventId + "/consents", guest,
                Map.of("items", List.of(Map.of("code", "photo", "version", 1, "decision", "AGREED")))), 200);
        return guest;
    }

    private String startRun(String guest) {
        return data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(PARTICIPANT, guest).exchange(), 201)
                .path("captureRunId").asString();
    }

    private List<String> uploadFour(String guest, String runId) {
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", guest,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            data(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(PARTICIPANT, guest).exchange(), 200);
            assetIds.add(assetId);
        }
        return assetIds;
    }

    private String compose(String guest, String runId, List<String> assetIds) {
        return data(post("/api/participant/events/" + eventId + "/compose-jobs", guest, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "k-" + runId)), 202)
                .path("resultToken").asString();
    }

    private MvcTestResult post(String uri, String token, Object body) {
        return mvc.post().uri(uri).header(PARTICIPANT, token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)).exchange();
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

    private JsonNode body(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
