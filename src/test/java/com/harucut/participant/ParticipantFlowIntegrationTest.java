package com.harucut.participant;

import com.harucut.entitlement.enums.Feature;
import com.harucut.event.service.EventFeatures;
import com.harucut.participant.config.ParticipationProperties;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.harucut.config.RateLimiter;
import com.harucut.event.batch.EventRetentionService;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.service.ComposeService;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.support.TestEntitlements;
import com.harucut.workspace.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/*
 * 로그인 없는 개인 휴대폰 참가 흐름을 실제 행사 보안 체인 + 컨트롤러 + JPA 로 끝까지 본다(ADR-0007 §5).
 * S3 와 Redis(빈도 제한)만 가짜다. 합성 실행(Lambda)은 커밋 뒤라 이 트랜잭션 테스트에서는 돌지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("행사 참가 — 통합")
class ParticipantFlowIntegrationTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;

    @Autowired private EntitlementRepository entitlementRepository;

    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private ComposeService composeService;
    @Autowired private EventRetentionService retentionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;
    @MockitoSpyBean private ParticipationProperties participationProperties;
    @MockitoSpyBean private EventFeatures eventFeatures;

    private String operatorPublicId;
    private String slug;
    private String eventId;
    private Long frameVersionId;
    private byte[] png;

    @BeforeEach
    void liveEvent() throws IOException {
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get-short");
        png = png();
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        User operator = userRepository.save(User.localUser("op@harucut.com", "encoded", "운영자"));
        operatorPublicId = operator.getPublicId();
        WorkspaceResponse org = workspaceService.createOrganization(operator.getPublicId(), "웨딩팀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        Long frameId = frameService.createFrame(operator.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(operator.getPublicId(), frameId).frameVersionId();

        slug = "wedding-" + UUID.randomUUID().toString().substring(0, 8);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo-collection", "version", 1,
                                "title", "사진 수집 동의", "required", true, "body", "신랑신부가 사진을 받습니다."))))));
        eventService.publish(operator.getPublicId(), created.eventId());
        eventId = created.eventId();
    }

    @Test
    @DisplayName("행사 조회 → 세션 → 동의 → 회차 → 업로드 4장 → 합성 → 결과 토큰 → 결과 조회·재방문 목록")
    void byodFlow() throws Exception {
        JsonNode event = ok(mvc.get().uri("/api/public/events/{slug}", slug).exchange());
        assertThat(event.path("eventId").asString()).isEqualTo(eventId);
        // 동의 화면은 운영자가 쓴 문구를 그대로 보여 준다 — 제목·본문·필수·버전이 실려야 한다
        JsonNode consentItem = event.path("consentItems").get(0);
        assertThat(consentItem.path("title").asString()).isEqualTo("사진 수집 동의");
        assertThat(consentItem.path("body").asString()).isEqualTo("신랑신부가 사진을 받습니다.");
        assertThat(consentItem.path("required").asBoolean()).isTrue();
        assertThat(consentItem.path("version").asInt()).isEqualTo(1);

        String token = data(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 201)
                .path("participantToken").asString();

        JsonNode restored = ok(mvc.get().uri("/api/participant/session").header(TOKEN_HEADER, token).exchange());
        assertThat(restored.path("status").asString()).isEqualTo("RESTRICTED");
        assertThat(restored.path("pendingConsents")).hasSize(1);

        // 동의 전에는 회차를 열 수 없다
        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 403, "PART-002");

        JsonNode consent = ok(post("/api/participant/events/" + eventId + "/consents", token,
                Map.of("items", List.of(Map.of("code", "photo-collection", "version", 1, "decision", "AGREED")))));
        assertThat(consent.path("status").asString()).isEqualTo("ACTIVE");

        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();

        ok(mvc.get().uri("/api/participant/events/{id}/frame-versions/{fv}?captureRunId={run}", eventId, frameVersionId, runId)
                .header(TOKEN_HEADER, token).exchange());

        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            JsonNode verified = ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId)
                    .header(TOKEN_HEADER, token).exchange());
            assertThat(verified.path("status").asString()).isEqualTo("VERIFIED");
            assetIds.add(assetId);
        }

        Map<String, Object> compose = Map.of("captureRunId", runId, "frameVersionId", frameVersionId,
                "sourceAssetIds", assetIds, "idempotencyKey", "key-1");
        JsonNode accepted = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose), 202);
        long jobId = accepted.path("jobId").asLong();
        String resultToken = accepted.path("resultToken").asString();
        assertThat(resultToken).isNotBlank();

        // 같은 키 재요청: 같은 작업, 새 결과 토큰
        JsonNode replay = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose), 202);
        assertThat(replay.path("jobId").asLong()).isEqualTo(jobId);
        assertThat(replay.path("resultToken").asString()).isNotEqualTo(resultToken);

        // 같은 키에 다른 입력 순서는 MEDIA-001
        List<String> reordered = new ArrayList<>(assetIds);
        java.util.Collections.reverse(reordered);
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                Map.of("captureRunId", runId, "frameVersionId", frameVersionId,
                        "sourceAssetIds", reordered, "idempotencyKey", "key-1")), 409, "MEDIA-001");

        JsonNode result = ok(mvc.get().uri("/api/public/results")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resultToken).exchange());
        assertThat(result.path("status").asString()).isEqualTo("PENDING");
        assertThat(result.path("eventName").asString()).isEqualTo("민지 ♥ 준호");

        // 준비 전 다운로드는 RESULT-003
        assertCode(mvc.post().uri("/api/public/results/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resultToken).exchange(), 409, "RESULT-003");

        JsonNode mine = ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("jobId").asLong()).isEqualTo(jobId);
        assertThat(mine.get(0).path("status").asString()).isEqualTo("PENDING");

        // 쓴 입력은 다른 작업에 다시 못 쓴다
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                Map.of("captureRunId", runId, "frameVersionId", frameVersionId,
                        "sourceAssetIds", assetIds, "idempotencyKey", "key-2")), 409, "PART-005");
    }

    @Test
    @DisplayName("작업이 확정되고 보관 배치가 돈 뒤에도 같은 합성 요청을 다시 보내면 같은 작업이다(입력 행은 재생 기간 동안 남는다)")
    void replayAfterRetentionRun() throws Exception {
        String token = activeParticipant();
        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        Map<String, Object> compose = Map.of("captureRunId", runId, "frameVersionId", frameVersionId,
                "sourceAssetIds", assetIds, "idempotencyKey", "key-replay");
        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose), 202).path("jobId").asLong();
        assertThat(composeService.completeJob(jobId, "uploads/r/replay.jpg", "uploads/r/replay_thumb.jpg")).isTrue();

        retentionService.run();

        assertThat(data(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose), 202).path("jobId").asLong())
                .isEqualTo(jobId);
    }

    @Test
    @DisplayName("결과 저장은 파일로 내려받는 URL(attachment)이고, 화면에는 결과 원본의 바로 보기 URL 을 준다 — 준비 전에는 둘 다 없다")
    void resultDeliveryUrls() throws Exception {
        given(fileStorageService.generatePresignedGetUrl(eq("uploads/r/delivery.jpg"), any())).willReturn("https://s3/image");
        given(fileStorageService.generatePresignedDownloadUrl(eq("uploads/r/delivery.jpg"), anyString(), any())).willReturn("https://s3/attachment");

        String token = activeParticipant();
        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        JsonNode accepted = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "key-delivery")), 202);
        long jobId = accepted.path("jobId").asLong();
        String bearer = "Bearer " + accepted.path("resultToken").asString();

        JsonNode pending = ok(mvc.get().uri("/api/public/results").header(HttpHeaders.AUTHORIZATION, bearer).exchange());
        assertThat(pending.has("imageUrl")).isFalse();
        assertThat(pending.has("thumbnailUrl")).isFalse();

        assertThat(composeService.completeJob(jobId, "uploads/r/delivery.jpg", "uploads/r/delivery_thumb.jpg")).isTrue();

        JsonNode done = ok(mvc.get().uri("/api/public/results").header(HttpHeaders.AUTHORIZATION, bearer).exchange());
        assertThat(done.path("status").asString()).isEqualTo("DONE");
        assertThat(done.path("imageUrl").asString()).isEqualTo("https://s3/image");
        // 썸네일은 목록용으로 그대로 — key 는 작업에서 계산되므로 기본 응답을 받는다
        assertThat(done.path("thumbnailUrl").asString()).isEqualTo("https://s3/get-short");

        // 결과 링크·재방문 다운로드 모두 attachment, 파일 이름은 행사 이름-찍은 시각.jpg (내부 작업 번호 없음)
        assertThat(ok(mvc.post().uri("/api/public/results/download").header(HttpHeaders.AUTHORIZATION, bearer).exchange())
                .path("url").asString()).isEqualTo("https://s3/attachment");
        assertThat(ok(mvc.post().uri("/api/participant/compose-jobs/{id}/download", jobId).header(TOKEN_HEADER, token).exchange())
                .path("url").asString()).isEqualTo("https://s3/attachment");

        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        then(fileStorageService).should(times(2))
                .generatePresignedDownloadUrl(eq("uploads/r/delivery.jpg"), filename.capture(), any());
        assertThat(filename.getAllValues()).allSatisfy(name -> assertThat(name).matches("민지 ♥ 준호-\\d{8}-\\d{6}\\.jpg"));
    }

    @Test
    @DisplayName("중지·종료된 행사에는 새 업로드 허가도 완료도 없다(R5 L302) — 이미 발급한 URL 의 업로드를 완료시키지 않는다")
    void stoppedEventBlocksUploads() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        String assetId = intent(token, runId, 0);

        entityManager.flush();
        jdbcTemplate.update("update event set status = 'ARCHIVED', archived_at = ? where public_id = ?", LocalDateTime.now(), eventId);
        entityManager.clear();

        assertCode(post("/api/participant/events/" + eventId + "/upload-intents", token,
                Map.of("captureRunId", runId, "slotIndex", 1, "contentType", "PNG", "fileSize", png.length)), 409, "EVENT-001");
        assertCode(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange(), 409, "EVENT-001");
    }

    @Test
    @DisplayName("끝난 회차의 늦은 업로드는 완료시키지 않는다(PART-003) — 이미 판정된 입력의 재확인은 그대로 답한다")
    void endedRunBlocksCompletion() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        String done = intent(token, runId, 0);
        ok(mvc.post().uri("/api/participant/assets/{id}/complete", done).header(TOKEN_HEADER, token).exchange());
        String late = intent(token, runId, 1);

        entityManager.flush();
        jdbcTemplate.update("update capture_run set expires_at = ? where public_id = ?", LocalDateTime.now().minusMinutes(1), runId);
        entityManager.clear();

        assertCode(mvc.post().uri("/api/participant/assets/{id}/complete", late).header(TOKEN_HEADER, token).exchange(), 409, "PART-003");
        assertThat(ok(mvc.post().uri("/api/participant/assets/{id}/complete", done).header(TOKEN_HEADER, token).exchange())
                .path("status").asString()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("재게시로 새 필수 동의가 생기면 ACTIVE 세션도 제한된다(R5 L545) — 세션 화면이 RESTRICTED 로 보여 주고, 동의하면 풀린다")
    void newRequiredConsentRestrictsActiveSession() throws Exception {
        String token = activeParticipant();

        entityManager.flush();
        Integer version = jdbcTemplate.queryForObject("select settings_version from event where public_id = ?", Integer.class, eventId);
        eventService.patch(operatorPublicId, eventId, new EventPatchRequest(version, objectMapper.valueToTree(Map.of(
                "consentItems", List.of(
                        Map.of("code", "photo-collection", "version", 1, "title", "사진 수집 동의", "required", true, "body", "신랑신부가 사진을 받습니다."),
                        Map.of("code", "marketing", "version", 1, "title", "홍보 활용", "required", true, "body", "홍보에 씁니다."))))));
        eventService.publish(operatorPublicId, eventId);

        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(TOKEN_HEADER, token).exchange(), 403, "PART-002");
        JsonNode session = ok(mvc.get().uri("/api/participant/session").header(TOKEN_HEADER, token).exchange());
        assertThat(session.path("status").asString()).isEqualTo("RESTRICTED");
        assertThat(session.path("pendingConsents").get(0).path("code").asString()).isEqualTo("marketing");

        ok(post("/api/participant/events/" + eventId + "/consents", token,
                Map.of("items", List.of(Map.of("code", "marketing", "version", 1, "decision", "AGREED")))));
        run(token);
    }

    @Test
    @DisplayName("접수 중단 스위치(R5 L694) — 새 세션을 막고, 이미 있는 세션의 조회는 그대로다")
    void acceptSwitchStopsNewWork() throws Exception {
        String token = activeParticipant();
        given(participationProperties.acceptEnabled()).willReturn(false);

        assertCode(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 409, "EVENT-001");
        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(TOKEN_HEADER, token).exchange(), 409, "EVENT-001");
        ok(mvc.get().uri("/api/participant/session").header(TOKEN_HEADER, token).exchange());
    }

    @Test
    @DisplayName("세션 하나의 남용 상한(R5 L675) — 회차당 묶이지 않은 입력 16개, 회차·업로드 발급은 분당 상한")
    void sessionAbuseLimits() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        for (int i = 0; i < 16; i++) {
            intent(token, runId, 0);
        }
        Map<String, Object> intent = Map.of("captureRunId", runId, "slotIndex", 0, "contentType", "PNG", "fileSize", png.length);
        assertCode(post("/api/participant/events/" + eventId + "/upload-intents", token, intent), 429, "GEN-061");

        given(rateLimiter.tryAcquire(eq("capture-run"), anyString(), anyInt(), any())).willReturn(false);
        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(TOKEN_HEADER, token).exchange(), 429, "GEN-061");
    }

    @Test
    @DisplayName("이용권의 디자인 기능이 회수되면 새 회차를 막는다(R5 L781, O32) — ENT-004")
    void revokedFeatureBlocksNewRun() throws Exception {
        org.mockito.BDDMockito.willReturn(true).given(eventFeatures).allows(any(), eq(Feature.CUSTOM_THEME));
        entityManager.flush();
        Integer version = jdbcTemplate.queryForObject("select settings_version from event where public_id = ?", Integer.class, eventId);
        eventService.patch(operatorPublicId, eventId, new EventPatchRequest(version, objectMapper.valueToTree(Map.of(
                "theme", Map.of("colors", Map.of("accent", "#112233"))))));
        eventService.publish(operatorPublicId, eventId);
        String token = activeParticipant();
        run(token);

        org.mockito.BDDMockito.willReturn(false).given(eventFeatures).allows(any(), eq(Feature.CUSTOM_THEME));

        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(TOKEN_HEADER, token).exchange(), 403, "ENT-004");
    }

    @Test
    @DisplayName("발행본에 촬영 입력 정책(CAMERA_ONLY)이 남는다(R5 L545, F46)")
    void publishedDocumentRecordsInputPolicy() {
        entityManager.flush();
        String json = jdbcTemplate.queryForObject("""
                select v.settings_json from event_experience_version v
                join event e on e.current_experience_version_id = v.event_experience_version_id
                where e.public_id = ?""", String.class, eventId);
        assertThat(json).contains("\"inputPolicy\":\"CAMERA_ONLY\"");
    }

    @Test
    @DisplayName("저장소에서 사라진 입력으로는 합성을 접수하지 않는다(R5 L552·L680) — 사용량을 예약하지 않고 PART-005")
    void missingObjectIsNotAccepted() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = intent(token, runId, slot);
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        given(fileStorageService.head(anyString())).willReturn(Optional.empty());

        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "key-missing")), 409, "PART-005");
    }

    @Test
    @DisplayName("설정 응답의 hasUnpublishedChanges — 게시 직후 false, 저장하면 true, 다시 게시하면 false")
    void unpublishedChangesFlag() {
        assertThat(eventService.get(operatorPublicId, eventId).hasUnpublishedChanges()).isFalse();

        int version = eventService.get(operatorPublicId, eventId).settingsVersion();
        eventService.patch(operatorPublicId, eventId, new EventPatchRequest(version, objectMapper.valueToTree(Map.of(
                "consentItems", List.of(Map.of("code", "photo-collection", "version", 2, "title", "사진 수집 동의", "required", true, "body", "문구를 고쳤습니다."))))));
        assertThat(eventService.get(operatorPublicId, eventId).hasUnpublishedChanges()).isTrue();

        eventService.publish(operatorPublicId, eventId);
        assertThat(eventService.get(operatorPublicId, eventId).hasUnpublishedChanges()).isFalse();
    }

    @Test
    @DisplayName("재방문 목록 — 끝난 작업에만 미리보기 URL 이 있다")
    void myResultsThumbnail() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = intent(token, runId, slot);
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "key-thumb")), 202).path("jobId").asLong();

        assertThat(ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange()).get(0).has("thumbnailUrl")).isFalse();

        assertThat(ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange()).get(0)
                .path("expiresAt").asString()).isNotBlank();
        assertThat(composeService.completeJob(jobId, "uploads/r/thumb.jpg", "uploads/r/thumb_thumb.jpg")).isTrue();
        assertThat(ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange()).get(0)
                .path("thumbnailUrl").asString()).isEqualTo("https://s3/get-short");

        // 보관 기한이 지났다 — 정리 배치가 돌기 전이어도 미리보기를 주지 않는다. 기한은 그대로 알린다(status·다운로드와 같은 판정)
        entityManager.flush();
        jdbcTemplate.update("update compose_job set retain_until = ? where compose_job_id = ?", LocalDateTime.now().minusMinutes(1), jobId);
        entityManager.clear();
        JsonNode expired = ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange()).get(0);
        assertThat(expired.has("thumbnailUrl")).isFalse();
        assertThat(expired.path("expiresAt").asString()).isNotBlank();
        assertCode(mvc.post().uri("/api/participant/compose-jobs/{id}/download", jobId).header(TOKEN_HEADER, token).exchange(), 410, "RESULT-002");
    }

    @Test
    @DisplayName("재방문 목록 — 실패한 작업은 실패 종류가 있어 화면 문구를 고를 수 있다")
    void myResultsFailureCode() throws Exception {
        String token = activeParticipant();
        String runId = run(token);
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = intent(token, runId, slot);
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "key-failed")), 202).path("jobId").asLong();

        assertThat(composeService.failJob(jobId, "렌더 실패", com.harucut.media.compose.ComposeFailureCode.RENDER_FAILED)).isTrue();

        JsonNode row = ok(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(TOKEN_HEADER, token).exchange()).get(0);
        assertThat(row.path("failureCode").asString()).isEqualTo("RENDER_FAILED");
        assertThat(row.has("thumbnailUrl")).isFalse();
    }

    @Test
    @DisplayName("보관 종료된 행사에는 합성을 접수하지 않는다 — 촬영 열림을 잠금으로 다시 읽은 값으로 본다")
    void composeRejectedAfterArchive() throws Exception {
        String token = activeParticipant();
        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            ok(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        // 다른 트랜잭션의 보관 종료가 커밋된 상황 — 영속성 컨텍스트를 거치지 않고 행만 바꾼다
        entityManager.flush();
        jdbcTemplate.update("update event set status = 'ARCHIVED', archived_at = ? where public_id = ?", LocalDateTime.now(), eventId);

        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "key-archived")), 409, "EVENT-001");
    }

    @Test
    @DisplayName("자격이 없거나 엉뚱하면 경로에 맞는 401 — 참가자 PART-001, 결과 RESULT-001")
    void credentials() {
        assertCode(mvc.get().uri("/api/participant/session").exchange(), 401, "PART-001");
        assertCode(mvc.get().uri("/api/participant/session").header(TOKEN_HEADER, "nope").exchange(), 401, "PART-001");
        assertCode(mvc.get().uri("/api/public/results").exchange(), 401, "RESULT-001");

        String token = data(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 201)
                .path("participantToken").asString();
        // 참가자 토큰을 결과 토큰 자리에 넣어도 통하지 않는다
        assertCode(mvc.get().uri("/api/public/results")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange(), 401, "RESULT-001");
        // 다른 출처의 프론트가 사용자 정의 헤더를 실을 수 있어야 한다(preflight)
        assertThat(mvc.options().uri("/api/participant/session")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, TOKEN_HEADER).exchange())
                .hasStatusOk()
                .headers().containsHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        // 회원 쿠키 경로가 아니다 — 행사 체인의 매처 밖 경로는 열리지 않는다
        assertThat(mvc.get().uri("/api/public/booth-pairings/anything").exchange()).hasStatus(403);
    }

    @Test
    @DisplayName("틀린 결과 토큰이 같은 IP 에서 한도를 넘으면 429 GEN-061 — 토큰이 없는 요청은 세지 않는다")
    void wrongResultTokensAreRateLimited() {
        given(rateLimiter.tryAcquire(eq("result-token-miss"), anyString(), anyInt(), any())).willReturn(false);

        assertCode(mvc.get().uri("/api/public/results")
                .header(HttpHeaders.AUTHORIZATION, "Bearer guessed").exchange(), 429, "GEN-061");
        assertCode(mvc.get().uri("/api/public/results").exchange(), 401, "RESULT-001");
    }

    @Test
    @DisplayName("게시 전·없는 slug 는 404, BOOTH 채널은 세션 발급 경로에서 받지 않는다")
    void publicEventGuards() {
        assertThat(mvc.get().uri("/api/public/events/{slug}", "no-such-event").exchange()).hasStatus(404);
        assertThat(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BOOTH\"}").exchange())
                .hasStatus4xxClientError();
    }

    // ── helpers ──────────────────────────────

    private String run(String token) {
        return data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();
    }

    private String intent(String token, String runId, int slot) {
        return data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                .path("assetId").asString();
    }

    // 세션 발급 + 필수 동의 → ACTIVE 참가자 토큰
    private String activeParticipant() {
        String token = data(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 201)
                .path("participantToken").asString();
        ok(post("/api/participant/events/" + eventId + "/consents", token,
                Map.of("items", List.of(Map.of("code", "photo-collection", "version", 1, "decision", "AGREED")))));
        return token;
    }

    private MvcTestResult post(String uri, String token, Object body) {
        return mvc.post().uri(uri).header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)).exchange();
    }

    private JsonNode ok(MvcTestResult result) {
        return data(result, 200);
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
            return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
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
