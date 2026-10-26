package com.harucut.booth;

import com.harucut.booth.dto.BoothDtos;
import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.common.exception.BusinessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 부스 흐름을 실제 행사 보안 체인 + 컨트롤러 + JPA 로 본다(ADR-0007 §4·§7):
 * 기기 등록 → 연결 코드 교환 → 기기 토큰 → 다음 손님 세션 → 참가자 경로로 촬영·합성 → 회차 확인 → 닫기.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("부스 — 통합")
class BoothFlowIntegrationTest {

    private static final String BOOTH = "X-Booth-Token";
    private static final String PARTICIPANT = "X-Participant-Token";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;

    @Autowired private EntitlementRepository entitlementRepository;

    @Autowired private WorkspaceRepository workspaceRepository;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User operator;
    private String eventId;
    private Long frameVersionId;
    private byte[] png;

    @BeforeEach
    void liveBoothEvent() throws IOException {
        png = png();
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get-short");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        operator = userRepository.save(User.localUser("op@harucut.com", "encoded", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(operator.getPublicId(), "웨딩팀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        Long frameId = frameService.createFrame(operator.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(operator.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(operator.getPublicId(), frameId).frameVersionId();
        String slug = "booth-" + UUID.randomUUID().toString().substring(0, 8);
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", slug, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(operator.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "boothEnabled", true,
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo", "version", 1,
                                "title", "사진 동의", "required", true, "body", "본문"))))));
        eventService.publish(operator.getPublicId(), created.eventId());
        eventId = created.eventId();
    }

    @Test
    @DisplayName("등록 → 교환 → me → 다음 손님 → 회차(기기당 1개) → 합성 → current(version 대조) → 결과 토큰 → 닫기")
    void boothFlow() {
        BoothDtos.DeviceResponse device = boothDeviceService.create(operator.getPublicId(), eventId, "입구 부스");
        assertThat(device.pairingCode()).matches("[A-Z2-7]{4}-[A-Z2-7]{4}");

        // 하이픈 없이·소문자로 넣어도 된다
        JsonNode redeemed = data(redeem(device.pairingCode().replace("-", "").toLowerCase()), 200);
        String boothToken = redeemed.path("boothToken").asString();
        assertThat(redeemed.path("eventId").asString()).isEqualTo(eventId);

        JsonNode me = data(mvc.get().uri("/api/booth/me").header(BOOTH, boothToken).exchange(), 200);
        assertThat(me.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(me.path("name").asString()).isEqualTo("입구 부스");
        assertThat(me.path("event").path("eventId").asString()).isEqualTo(eventId);
        assertThat(me.path("event").path("consentItems").get(0).path("title").asString()).isEqualTo("사진 동의");

        // 활성 회차가 없으면 data 키가 없다
        assertThat(body(mvc.get().uri("/api/booth/capture-runs/current").header(BOOTH, boothToken).exchange()).has("data")).isFalse();

        String guest = nextGuest(boothToken);
        String runId = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(PARTICIPANT, guest).exchange(), 201).path("captureRunId").asString();

        // 같은 기기의 다른 손님은 회차를 못 연다
        String other = nextGuest(boothToken);
        assertCode(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(PARTICIPANT, other).exchange(), 409, "BOOTH-004");

        JsonNode current = data(mvc.get().uri("/api/booth/capture-runs/current?version=1").header(BOOTH, boothToken).exchange(), 200);
        assertThat(current.path("captureRunId").asString()).isEqualTo(runId);
        assertThat(current.path("stateVersion").asInt()).isEqualTo(1);
        assertThat(current.has("job")).isFalse();

        long jobId = compose(guest, runId);
        assertThat(data(mvc.get().uri("/api/booth/capture-runs/current?version=1").header(BOOTH, boothToken).exchange(), 200)
                .path("job").path("jobId").asLong()).isEqualTo(jobId);
        // 화면이 다른 version 을 들고 있으면 작업을 싣지 않는다
        assertThat(data(mvc.get().uri("/api/booth/capture-runs/current?version=7").header(BOOTH, boothToken).exchange(), 200)
                .has("job")).isFalse();

        // 부스 세션은 결과 목록을 볼 수 없다
        assertCode(mvc.get().uri("/api/participant/events/{id}/results", eventId).header(PARTICIPANT, guest).exchange(),
                403, "PART-006");

        JsonNode token = data(mvc.post().uri("/api/booth/capture-runs/{id}/result-access", runId).header(BOOTH, boothToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateVersion\":1}").exchange(), 201);
        assertThat(token.path("resultToken").asString()).isNotBlank();

        JsonNode closed = data(mvc.post().uri("/api/booth/capture-runs/{id}/close", runId).header(BOOTH, boothToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateVersion\":1}").exchange(), 200);
        assertThat(closed.path("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.path("stateVersion").asInt()).isEqualTo(2);
        // 닫은 손님의 세션은 끝났다, 같은 version 으로 다시 닫으면 BOOTH-005
        assertCode(mvc.get().uri("/api/participant/session").header(PARTICIPANT, guest).exchange(), 401, "PART-001");
        assertCode(mvc.post().uri("/api/booth/capture-runs/{id}/close", runId).header(BOOTH, boothToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateVersion\":1}").exchange(), 409, "BOOTH-005");

        // 다음 손님은 회차를 열 수 있다
        assertThat(mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId)
                .header(PARTICIPANT, other).exchange()).hasStatus(201);
    }

    @Test
    @DisplayName("연결 코드: 틀린 비밀 4번은 BOOTH-001, 5번째에 닫혀 BOOTH-002 — 그 뒤엔 맞는 코드도 BOOTH-002")
    void pairingLockout() {
        String code = boothDeviceService.create(operator.getPublicId(), eventId, "부스").pairingCode();
        String wrong = code.substring(0, 5) + flip(code.substring(5));
        for (int i = 0; i < 4; i++) {
            assertCode(redeem(wrong), 400, "BOOTH-001");
        }
        assertCode(redeem(wrong), 410, "BOOTH-002");
        assertCode(redeem(code), 410, "BOOTH-002");
        // 열린 거래가 없는 lookup·모양이 틀린 코드
        assertCode(redeem("2222-2222"), 400, "BOOTH-001");
        assertCode(redeem("ABC"), 400, "BOOTH-001");
    }

    @Test
    @DisplayName("코드는 한 번만, 재발급 코드가 교환되면 이전 기기 토큰은 BOOTH-003")
    void reissueReplacesToken() {
        BoothDtos.DeviceResponse device = boothDeviceService.create(operator.getPublicId(), eventId, "부스");
        String first = data(redeem(device.pairingCode()), 200).path("boothToken").asString();
        assertCode(redeem(device.pairingCode()), 400, "BOOTH-001");

        String code = boothDeviceService.reissueCode(operator.getPublicId(), device.deviceId()).pairingCode();
        // 새 코드가 교환되기 전에는 이전 토큰이 계속 된다
        assertThat(mvc.get().uri("/api/booth/me").header(BOOTH, first).exchange()).hasStatusOk();
        String second = data(redeem(code), 200).path("boothToken").asString();

        assertCode(mvc.get().uri("/api/booth/me").header(BOOTH, first).exchange(), 403, "BOOTH-003");
        assertThat(mvc.get().uri("/api/booth/me").header(BOOTH, second).exchange()).hasStatusOk();
    }

    @Test
    @DisplayName("회수: 기기 토큰 BOOTH-003, 그 기기 손님 세션 회수, 진행 중 회차 종료")
    void revoke() {
        BoothDtos.DeviceResponse device = boothDeviceService.create(operator.getPublicId(), eventId, "부스");
        String boothToken = data(redeem(device.pairingCode()), 200).path("boothToken").asString();
        String guest = nextGuest(boothToken);
        mvc.post().uri("/api/participant/events/{id}/capture-runs", eventId).header(PARTICIPANT, guest).exchange();

        assertThat(boothDeviceService.revoke(operator.getPublicId(), device.deviceId()).status().name()).isEqualTo("REVOKED");

        assertCode(mvc.get().uri("/api/booth/me").header(BOOTH, boothToken).exchange(), 403, "BOOTH-003");
        assertCode(mvc.get().uri("/api/participant/session").header(PARTICIPANT, guest).exchange(), 401, "PART-001");
        assertThatThrownBy(() -> boothDeviceService.reissueCode(operator.getPublicId(), device.deviceId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(BoothErrorCode.DEVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("기기 토큰이 없으면 BOOTH-003, 기기 수 상한은 BOOTH-006(회수한 기기는 세지 않는다), 운영자가 아니면 404")
    void guards() {
        assertCode(mvc.get().uri("/api/booth/me").exchange(), 403, "BOOTH-003");

        List<BoothDtos.DeviceResponse> devices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            devices.add(boothDeviceService.create(operator.getPublicId(), eventId, "부스 " + i));
        }
        assertThatThrownBy(() -> boothDeviceService.create(operator.getPublicId(), eventId, "21번째"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(BoothErrorCode.DEVICE_LIMIT);
        boothDeviceService.revoke(operator.getPublicId(), devices.get(0).deviceId());
        assertThat(boothDeviceService.create(operator.getPublicId(), eventId, "자리 남").status().name()).isEqualTo("PENDING_PAIRING");
        assertThat(boothDeviceService.list(operator.getPublicId(), eventId)).hasSize(21)
                .allSatisfy(listed -> assertThat(listed.pairingCode()).isNull());

        User stranger = userRepository.save(User.localUser("s@harucut.com", "e", "s"));
        assertThatThrownBy(() -> boothDeviceService.list(stranger.getPublicId(), eventId))
                .isInstanceOf(BusinessException.class);
    }

    // ── helpers ──────────────────────────────

    private String nextGuest(String boothToken) {
        String guest = data(mvc.post().uri("/api/booth/participant-sessions").header(BOOTH, boothToken).exchange(), 201)
                .path("participantToken").asString();
        data(post("/api/participant/events/" + eventId + "/consents", guest,
                Map.of("items", List.of(Map.of("code", "photo", "version", 1, "decision", "AGREED")))), 200);
        return guest;
    }

    private long compose(String guest, String runId) {
        List<String> assetIds = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = data(post("/api/participant/events/" + eventId + "/upload-intents", guest,
                    Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                    .path("assetId").asString();
            data(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(PARTICIPANT, guest).exchange(), 200);
            assetIds.add(assetId);
        }
        return data(post("/api/participant/events/" + eventId + "/compose-jobs", guest, Map.of("captureRunId", runId,
                "frameVersionId", frameVersionId, "sourceAssetIds", assetIds, "idempotencyKey", "k-" + runId)), 202)
                .path("jobId").asLong();
    }

    private MvcTestResult redeem(String code) {
        return mvc.post().uri("/api/public/booth-pairings/redeem").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))).exchange();
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

    // 비밀 4자의 첫 글자를 다른 base32 문자로
    private static String flip(String secret) {
        char replaced = secret.charAt(0) == 'A' ? 'B' : 'A';
        return replaced + secret.substring(1);
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
