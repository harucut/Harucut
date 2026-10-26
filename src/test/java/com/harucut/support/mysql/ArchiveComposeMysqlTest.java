package com.harucut.support.mysql;

import com.harucut.config.RateLimiter;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.repository.ComposeJobRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P2B-6 — 보관 종료와 참가자 합성 접수가 같은 행사 행 잠금으로 줄을 서는지, 실제 MySQL.
 * 보관 종료 트랜잭션이 행사 행을 잠그고 ARCHIVED 로 바꾼 채(커밋 전) 접수가 들어오면, 접수는 기다렸다가
 * 커밋된 ARCHIVED 를 보고 거절해야 한다(예전에는 스냅샷의 LIVE 로 통과해 ARCHIVED 행사에 작업이 남았다).
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("보관 종료 ↔ 합성 접수 — 실제 MySQL 동시성")
class ArchiveComposeMysqlTest {

    private static final String TOKEN_HEADER = "X-Participant-Token";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    @Test
    @DisplayName("보관 종료가 커밋 전 잠금을 쥔 동안 들어온 접수는 기다렸다가 409 EVENT-001, 작업이 생기지 않는다")
    void composeWaitsForArchiveAndIsRejected() throws Exception {
        byte[] png = png();
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        User operator = userRepository.save(User.localUser("op@archive.test", "e", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(operator.getPublicId(), "웨딩팀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        Long frameVersionId = frameVersionService.publish(operator.getPublicId(), frameService.createFrame(operator.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId())
                .frameVersionId();
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "archive-race", null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        eventService.publish(operator.getPublicId(), created.eventId());
        String eventId = created.eventId();
        Long eventDbId = eventRepository.findByPublicId(eventId).orElseThrow().getId();

        String token = data(mvc.post().uri("/api/public/events/{slug}/sessions", "archive-race")
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 201)
                .path("participantToken").asString();
        data(post("/api/participant/events/" + eventId + "/consents", token,
                Map.of("items", List.of(Map.of("code", "c", "version", 1, "decision", "AGREED")))), 200);
        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(TOKEN_HEADER, token).exchange(), 201).path("captureRunId").asString();
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", token,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            data(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(TOKEN_HEADER, token).exchange(), 200);
            assetIds.add(assetId);
        }

        // 보관 종료 트랜잭션 — 행사 행을 쓰기 잠금으로 잡고 ARCHIVED 로 바꾼 채 커밋을 미룬다
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> archive = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            Event event = eventRepository.lockById(eventDbId).orElseThrow();
            event.archive(LocalDateTime.now());
            eventRepository.flush();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcTestResult> compose = CompletableFuture.supplyAsync(() -> post("/api/participant/events/" + eventId
                + "/compose-jobs", token, Map.of("captureRunId", runId, "frameVersionId", frameVersionId,
                "sourceAssetIds", assetIds, "idempotencyKey", "race-1")));
        Thread.sleep(1000);
        assertThat(compose).isNotDone();   // 행사 행 잠금에서 기다린다
        release.countDown();
        archive.get(20, TimeUnit.SECONDS);

        JsonNode body = body(compose.get(30, TimeUnit.SECONDS));
        assertThat(body.path("code").asString()).as(body.toString()).isEqualTo("EVENT-001");
        assertThat(composeJobRepository.findAll()).noneMatch(job -> eventDbId.equals(job.getEventId()));
    }

    private MvcTestResult post(String uri, String token, Object body) {
        return mvc.post().uri(uri).header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)).exchange();
    }

    private JsonNode data(MvcTestResult result, int status) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        return body.path("data");
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png() throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
