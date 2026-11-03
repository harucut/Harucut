package com.harucut.support.mysql;

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
import com.harucut.media.batch.ComposeRerunScheduler;
import com.harucut.media.compose.LambdaComposeExecutor;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeRequestedEvent;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;

/*
 * R5 L714·O44 — 실제 MySQL 에서 User 없는 참가자 작업의 생성 → 적체 조회 → 재접수 → 완료·실패 → 사용량 원장까지.
 * findStalled 가 user 없는 작업을 조용히 빠뜨리면 적체 수에는 있는데 복구되지 않는 작업이 생긴다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql",
        // 배치는 이 시험이 직접 돌린다
        "compose.rerun.interval=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("참가자 작업 전 과정 — 실제 MySQL")
class ParticipantJobLifecycleMysqlTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private ComposeService composeService;
    @Autowired private ComposeRerunScheduler rerunScheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;
    @MockitoBean private LambdaComposeExecutor composeExecutor;

    private String eventId;
    private Long frameVersionId;
    private String token;
    private byte[] png;

    @BeforeEach
    void liveEvent() throws Exception {
        png = png();
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        String unique = UUID.randomUUID().toString().substring(0, 8);
        User operator = userRepository.save(User.localUser("op-" + unique + "@lifecycle.test", "e", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(operator.getPublicId(), "팀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        frameVersionId = frameVersionService.publish(operator.getPublicId(), frameService.createFrame(operator.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId())
                .frameVersionId();
        String slug = "life-" + unique;
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        eventService.publish(operator.getPublicId(), created.eventId());
        eventId = created.eventId();

        token = body(mvc.post().uri("/api/public/events/{slug}/sessions", slug)
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange()).path("data").path("participantToken").asString();
        body(post("/api/participant/events/" + eventId + "/consents", Map.of("items", List.of(Map.of("code", "c", "version", 1, "decision", "AGREED")))));
    }

    private long submit(String idempotencyKey) throws Exception {
        String runId = body(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange()).path("data").path("captureRunId").asString();
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = body(post("/api/participant/events/" + eventId + "/upload-intents",
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)))
                    .path("data").path("assetId").asString();
            body(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange());
            assetIds.add(assetId);
        }
        return body(post("/api/participant/events/" + eventId + "/compose-jobs", Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", idempotencyKey)))
                .path("data").path("jobId").asLong();
    }

    private String ledgerStatus(long jobId) {
        return jdbcTemplate.queryForObject("select status from usage_reservation where compose_job_id = ?", String.class, jobId);
    }

    @Test
    @DisplayName("User 없는 작업이 적체 조회에서 빠지지 않고 재접수되며, 완료하면 예약이 확정되고 보관 약정이 굳어 있다")
    void stalledThenCompleted() throws Exception {
        long jobId = submit("life-done");
        then(composeExecutor).should(timeout(5000)).execute(any(ComposeRequestedEvent.class));
        ComposeJob accepted = composeJobRepository.findById(jobId).orElseThrow();
        assertThat(accepted.getUser()).isNull();
        assertThat(accepted.getRetainUntil()).isNotNull();
        assertThat(accepted.getInputFingerprint()).hasSize(64);
        assertThat(accepted.getAttemptNo()).isEqualTo(1);

        // 통지가 오지 않은 채 시간이 지났다
        jdbcTemplate.update("update compose_job set started_at = ?, next_attempt_at = ? where compose_job_id = ?",
                LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), jobId);
        rerunScheduler.run();

        ComposeJob redispatched = composeJobRepository.findById(jobId).orElseThrow();
        assertThat(redispatched.getAttemptNo()).isEqualTo(2);
        assertThat(redispatched.getStatus()).isEqualTo(ComposeStatus.PENDING);

        assertThat(composeService.completeJob(jobId, "uploads/events/e/results/life.jpg", "uploads/events/e/results/life_thumb.jpg")).isTrue();
        assertThat(ledgerStatus(jobId)).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("총 기한을 넘기면 DISPATCH_EXHAUSTED 최종 실패 — 예약이 돌아오고, 늦은 성공 통지가 되살리지 못한다")
    void exhaustedThenLateSuccess() throws Exception {
        long jobId = submit("life-exhausted");
        then(composeExecutor).should(timeout(5000)).execute(any(ComposeRequestedEvent.class));

        jdbcTemplate.update("update compose_job set created_at = ? where compose_job_id = ?", LocalDateTime.now().minusHours(3), jobId);
        rerunScheduler.run();

        ComposeJob ended = composeJobRepository.findById(jobId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ComposeStatus.FAILED);
        assertThat(ended.getFailureCode()).isEqualTo(ComposeFailureCode.DISPATCH_EXHAUSTED);
        assertThat(ledgerStatus(jobId)).isEqualTo("RELEASED");

        assertThat(composeService.completeJob(jobId, "uploads/events/e/results/late.jpg", "uploads/events/e/results/late_thumb.jpg")).isFalse();
        assertThat(ledgerStatus(jobId)).isEqualTo("RELEASED");
    }

    private MvcTestResult post(String uri, Object body) throws Exception {
        return mvc.post().uri(uri).header(TOKEN_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)).exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
