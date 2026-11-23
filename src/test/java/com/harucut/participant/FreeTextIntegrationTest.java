package com.harucut.participant;

import com.harucut.config.RateLimiter;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.asset.repository.AssetRepository;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
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


/*
 * 참가자 자유 문구(R5 D06) — 주최자 설정 → 공개 조회 → 서버 렌더 미리보기 → 합성(서버가 구운 PNG 를 TEXT_RENDER 로 기록·맨 위 레이어)
 * → 재생 대조 → 확정 때 PNG 정리 예약. S3 와 빈도 제한만 가짜다(ParticipantFlowIntegrationTest 와 같은 컨텍스트).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("참가자 자유 문구 — 통합")
class FreeTextIntegrationTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";
    private static final Map<String, Object> FREE_TEXT = Map.of("enabled", true,
            "box", Map.of("x", 0.1, "y", 0.85, "width", 0.8, "height", 0.1),
            "styleIds", List.of("pretendard-bold-dark", "pretendard-bold-light"), "maxLength", 10);

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
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User operator;
    private Long workspaceId;
    private String slug;
    private String eventId;
    private Long frameVersionId;
    private byte[] png;

    @BeforeEach
    void event() throws IOException {
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get-short");
        png = png();
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        operator = userRepository.save(User.localUser("free@harucut.com", "encoded", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(operator.getPublicId(), "문구팀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        Long frameId = frameService.createFrame(operator.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(operator.getPublicId(), frameId).frameVersionId();

        slug = "free-" + UUID.randomUUID().toString().substring(0, 8);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                new EventSettings("문구 행사", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventId = created.eventId();
        patch(Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "photo-collection", "version", 1,
                        "title", "사진 수집 동의", "required", true, "body", "주최자가 사진을 받습니다."))));
        eventService.publish(operator.getPublicId(), eventId);
    }

    @Test
    @DisplayName("고정 행사: 공개 조회 freeText=null, 미리보기·합성의 문구는 PART-007, 스타일 목록은 공개")
    void fixedEvent() throws Exception {
        JsonNode event = ok(mvc.get().uri("/api/public/events/{slug}", slug).exchange());
        assertThat(event.has("freeText")).isTrue();
        assertThat(event.path("freeText").isNull()).isTrue();
        assertThat(ok(mvc.get().uri("/api/public/text-styles").exchange())).hasSize(4);

        String token = activeParticipant();
        Run run = run(token);
        assertCode(preview(token, run, "안녕", "pretendard-bold-dark"), 403, "PART-007");
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose(run, "k-fixed",
                Map.of("text", "안녕", "styleId", "pretendard-bold-dark"))), 403, "PART-007");
    }

    @Test
    @DisplayName("자유 문구 행사: 미리보기 = 서버 렌더 PNG(칸 px), 검증 실패는 PART-008(data.reason), 합성은 TEXT_RENDER 기록·맨 위 레이어·재생 대조")
    void freeTextFlow() throws Exception {
        patch(Map.of("freeText", FREE_TEXT));
        eventService.publish(operator.getPublicId(), eventId);

        JsonNode offered = ok(mvc.get().uri("/api/public/events/{slug}", slug).exchange()).path("freeText");
        assertThat(offered.path("maxLength").asInt()).isEqualTo(10);
        assertThat(offered.path("maxLines").asInt()).isEqualTo(1);
        assertThat(offered.path("box").path("y").asDouble()).isEqualTo(0.85);
        assertThat(offered.path("styles")).hasSize(2);
        assertThat(offered.path("styles").get(0).path("id").asString()).isEqualTo("pretendard-bold-dark");
        assertThat(offered.has("enabled")).isFalse();

        String token = activeParticipant();
        Run run = run(token);
        JsonNode preview = ok(preview(token, run, "민지 ♥ 준호", "pretendard-bold-dark"));
        assertThat(preview.path("text").asString()).isEqualTo("민지 ♥ 준호");
        assertThat(preview.path("styleId").asString()).isEqualTo("pretendard-bold-dark");
        int canvasWidth = preview.path("canvasWidth").asInt();
        int canvasHeight = preview.path("canvasHeight").asInt();
        assertThat(preview.path("x").asInt()).isEqualTo((int) Math.round(0.1 * canvasWidth));
        assertThat(preview.path("width").asInt()).isEqualTo((int) Math.round(0.8 * canvasWidth));
        assertThat(preview.path("height").asInt()).isEqualTo((int) Math.round(0.1 * canvasHeight));
        String image = preview.path("image").asString();
        assertThat(image).startsWith("data:image/png;base64,");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(image.substring("data:image/png;base64,".length()))));
        assertThat(decoded.getWidth()).isEqualTo(preview.path("width").asInt());

        JsonNode tooLong = error(preview(token, run, "가".repeat(11), "pretendard-bold-dark"), 400, "PART-008");
        assertThat(tooLong.path("data").path("reason").asString()).isEqualTo("TOO_LONG");
        assertThat(tooLong.path("data").path("maxLength").asInt()).isEqualTo(10);
        assertThat(error(preview(token, run, "안녕", "pretendard-regular-dark"), 400, "PART-008")
                .path("data").path("reason").asString()).isEqualTo("STYLE_NOT_OFFERED");
        assertThat(error(preview(token, run, "축하 😀", "pretendard-bold-dark"), 400, "PART-008")
                .path("data").path("reason").asString()).isEqualTo("UNSUPPORTED_CHARACTER");

        Map<String, Object> request = compose(run, "k-text", Map.of("text", " 민지 ♥ 준호 ", "styleId", "pretendard-bold-dark"));
        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, request), 202).path("jobId").asLong();

        var job = composeJobRepository.findById(jobId).orElseThrow();
        var top = job.getSpec().layers().stream().max(java.util.Comparator.comparingInt(l -> l.zIndex())).orElseThrow();
        assertThat(top.source()).startsWith(job.getStorageRoot() + "text/").endsWith(".png");
        assertThat(top.width()).isEqualTo(preview.path("width").asInt());
        then(fileStorageService).should().uploadBytes(eq(top.source()), any(), eq("image/png"));
        var asset = assetRepository.findServerTextRenderByJob(jobId).orElseThrow();
        assertThat(asset.getS3Key()).isEqualTo(top.source());
        assertThat(asset.getTextSnapshot()).isEqualTo("민지 ♥ 준호");
        assertThat(asset.getTextStyleId()).isEqualTo("pretendard-bold-dark");
        assertThat(asset.getCaptureRunId()).isNotNull();
        assertThat(asset.getOrigin().name()).isEqualTo("SERVER");

        // 같은 키: 공백만 다른 같은 문구는 같은 작업, 다른 문구·문구 빼기는 MEDIA-001
        assertThat(data(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                compose(run, "k-text", Map.of("text", "민지 ♥ 준호", "styleId", "pretendard-bold-dark"))), 202)
                .path("jobId").asLong()).isEqualTo(jobId);
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                compose(run, "k-text", Map.of("text", "민지 ♥ 준호!", "styleId", "pretendard-bold-dark"))), 409, "MEDIA-001");
        assertCode(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose(run, "k-text", null)), 409, "MEDIA-001");

        // 미참조 자산 정리는 서버가 구운 문구를 고르지 않는다 — 진행 중인 작업의 레이어를 지우지 않게(작업 확정이 지운다)
        assertThat(assetRepository.findOrphanCandidates(java.time.LocalDateTime.now().plusYears(1), 0L,
                org.springframework.data.domain.PageRequest.of(0, 1000))).noneMatch(a -> a.getId().equals(asset.getId()));

        // 확정되면 원본과 함께 문구 PNG 도 지울 예약이 남는다(작업 전용 폴더의 서버 생성 레이어)
        composeService.completeJob(jobId, ComposeService.resultKeyFor(job), ComposeService.thumbnailKeyFor(job));
        assertThat(jdbcTemplate.queryForObject("select count(*) from s3_deletion where s3_key = ?", Integer.class, top.source()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#45 Codex: 촬영 중 재게시돼도 미리보기는 회차에 고정된 발행본으로 — 합성과 같은 정책, 새 회차는 새 발행본")
    void previewUsesRunsExperienceVersion() throws Exception {
        patch(Map.of("freeText", FREE_TEXT));
        eventService.publish(operator.getPublicId(), eventId);
        String token = activeParticipant();
        Run run = run(token);

        // 촬영 중에 자유 문구를 끄고 다시 게시
        patch(Map.of("freeText", Map.of("enabled", false)));
        eventService.publish(operator.getPublicId(), eventId);

        // 이 회차는 예전 발행본(문구 허용) 그대로 — 미리보기·합성 모두 된다
        assertThat(ok(preview(token, run, "안녕", "pretendard-bold-dark")).path("image").asString()).startsWith("data:image/png");
        data(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                compose(run, "k-pinned", Map.of("text", "안녕", "styleId", "pretendard-bold-dark"))), 202);

        // 회차가 아니거나 남의 회차면 PART-003
        assertCode(post("/api/participant/events/" + eventId + "/text-preview", token, Map.of("captureRunId", "nope",
                "frameVersionId", frameVersionId, "text", "안녕", "styleId", "pretendard-bold-dark")), 409, "PART-003");
    }

    @Test
    @DisplayName("문구를 안 보내면 자유 문구 행사여도 프레임 그대로 합성한다(문구 레이어·자산 없음)")
    void withoutFreeText() throws Exception {
        patch(Map.of("freeText", FREE_TEXT));
        eventService.publish(operator.getPublicId(), eventId);
        String token = activeParticipant();
        Run run = run(token);

        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token, compose(run, "k-none", null)), 202)
                .path("jobId").asLong();

        assertThat(assetRepository.findServerTextRenderByJob(jobId)).isEmpty();
        var job = composeJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getSpec().layers()).noneMatch(l -> l.source().startsWith(job.getStorageRoot()));
    }

    @Test
    @DisplayName("출력 로고(ORGANIZER_LOGO): 주최자 로고를 오른쪽 아래 고정 자리에 맨 위로(문구보다 위) 얹는다, 로고 없이 켜면 게시 위반")
    void outputLogo() throws Exception {
        com.harucut.asset.entity.Asset logo = com.harucut.asset.entity.Asset.pendingUpload(
                com.harucut.asset.entity.Asset.newPublicId(), workspaceId, com.harucut.asset.enums.AssetPurpose.PAGE_THEME_ASSET,
                "uploads/workspaces/w/theme/logo.png", "image/png", 100, operator.getId());
        logo.verify(100, 200, 100, java.time.LocalDateTime.now());
        assetRepository.save(logo);

        patch(Map.of("theme", Map.of("branding", Map.of("outputLogo", "ORGANIZER_LOGO"))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> eventService.publish(operator.getPublicId(), eventId))
                .isInstanceOf(com.harucut.common.exception.BusinessException.class);

        patch(Map.of("freeText", FREE_TEXT,
                "theme", Map.of("logoAssetId", logo.getPublicId(), "branding", Map.of("outputLogo", "ORGANIZER_LOGO"))));
        eventService.publish(operator.getPublicId(), eventId);
        String token = activeParticipant();
        Run run = run(token);

        long jobId = data(post("/api/participant/events/" + eventId + "/compose-jobs", token,
                compose(run, "k-logo", Map.of("text", "안녕", "styleId", "pretendard-bold-dark"))), 202).path("jobId").asLong();

        var spec = composeJobRepository.findById(jobId).orElseThrow().getSpec();
        var layers = spec.layers().stream().sorted(java.util.Comparator.comparingInt(l -> l.zIndex())).toList();
        var top = layers.get(layers.size() - 1);
        assertThat(top.source()).isEqualTo(logo.getS3Key());
        assertThat(layers.get(layers.size() - 2).source()).contains("/text/");
        int width = (int) Math.round(spec.canvasWidth() * 0.18);
        int margin = (int) Math.round(spec.canvasWidth() * 0.03);
        assertThat(top.width()).isEqualTo(width);
        assertThat(top.height()).isEqualTo(Math.round(width * 0.5));
        assertThat(top.x()).isEqualTo(spec.canvasWidth() - margin - width);
        assertThat(top.y()).isEqualTo(spec.canvasHeight() - margin - top.height());
    }

    // ── helpers ──────────────────────────────

    private record Run(String runId, List<String> assetIds) {
    }

    private void patch(Map<String, Object> settings) {
        EventResponse current = eventService.get(operator.getPublicId(), eventId);
        eventService.patch(operator.getPublicId(), eventId,
                new EventPatchRequest(current.settingsVersion(), objectMapper.valueToTree(settings)));
    }

    private Run run(String token) {
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
        return new Run(runId, assetIds);
    }

    private MvcTestResult preview(String token, Run run, String text, String styleId) {
        return post("/api/participant/events/" + eventId + "/text-preview", token, Map.of("captureRunId", run.runId(),
                "frameVersionId", frameVersionId, "text", text, "styleId", styleId));
    }

    private Map<String, Object> compose(Run run, String key, Map<String, Object> freeText) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("captureRunId", run.runId(),
                "frameVersionId", frameVersionId, "sourceAssetIds", run.assetIds(), "idempotencyKey", key));
        if (freeText != null) {
            body.put("freeText", freeText);
        }
        return body;
    }

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

    private JsonNode error(MvcTestResult result, int status, String code) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        return body;
    }

    private void assertCode(MvcTestResult result, int status, String code) {
        error(result, status, code);
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
