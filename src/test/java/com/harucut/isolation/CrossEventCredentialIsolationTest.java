package com.harucut.isolation;

import com.harucut.booth.service.BoothDeviceService;
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
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P3-6 격리 검수 (R5 21절, ADR-0009 §7-2) — 행사 자격(참가자 토큰·부스 토큰)은 그 행사 안에서만 쓰인다.
 * 고객 A 행사의 참가자·부스 기기가 고객 B 행사의 회차·작업·자산·발행본 id 로 참가·부스 API 를 부른다.
 * 대상은 계약에서 읽는다(회원 API 전수 검사와 같은 방식 — CrossTenantIsolationTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("격리 — 행사 자격의 교차 사용(계약 전수)")
class CrossEventCredentialIsolationTest {

    private static final String BOOTH = "X-Booth-Token";
    private static final String PARTICIPANT = "X-Participant-Token";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private EventService eventService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private BoothDeviceService boothDeviceService;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private byte[] png;
    private String participantA;
    private String boothA;
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final Map<String, String> bodies = new LinkedHashMap<>();

    record Live(String eventId, String slug, Long frameVersionId) {
    }

    @BeforeEach
    void twoLiveEvents() throws IOException {
        png = png();
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.presignPut(anyString(), anyString(), anyLong(), any(), anyBoolean()))
                .willReturn(new PresignedPut("https://s3/put", Map.of(), Instant.now().plusSeconds(600)));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get");
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);

        Live a = live("a@a.com", "A사", "a-live");
        Live b = live("b@b.com", "B사", "b-live");

        // B — 부스 손님이 회차를 열고 4장을 올려 합성까지 했다, 다섯 번째 자산은 올리기만 했다
        String boothB = boothToken(b);
        String guestB = data(mvc.post().uri("/api/booth/participant-sessions").header(BOOTH, boothB).exchange(), 201)
                .path("participantToken").asString();
        consent(guestB, b);
        String runB = data(mvc.post().uri("/api/participant/events/{id}/capture-runs", b.eventId())
                .header(PARTICIPANT, guestB).exchange(), 201).path("captureRunId").asString();
        List<String> assetsB = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            String assetId = uploadIntent(guestB, b, runB, slot);
            data(mvc.post().uri("/api/participant/assets/{id}/complete", assetId).header(PARTICIPANT, guestB).exchange(), 200);
            assetsB.add(assetId);
        }
        String jobB = data(post("/api/participant/events/" + b.eventId() + "/compose-jobs", guestB, Map.of("captureRunId", runB,
                "frameVersionId", b.frameVersionId(), "sourceAssetIds", assetsB, "idempotencyKey", "k-b")), 202).path("jobId").asString();

        // A — BYOD 참가자(동의 완료)와 연결된 부스 기기
        participantA = data(mvc.post().uri("/api/public/events/{slug}/sessions", a.slug())
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BYOD\"}").exchange(), 201)
                .path("participantToken").asString();
        consent(participantA, a);
        boothA = boothToken(a);

        ids.put("eventId", b.eventId());
        ids.put("captureRunId", runB);
        ids.put("jobId", jobB);
        ids.put("assetId", uploadIntent(guestB, b, runB, 0));
        ids.put("frameVersionId", b.frameVersionId().toString());
        bodies.put("POST /api/participant/events/{eventId}/compose-jobs", json(Map.of("captureRunId", runB,
                "frameVersionId", b.frameVersionId(), "sourceAssetIds", assetsB, "idempotencyKey", "k-intrude")));
        bodies.put("POST /api/participant/events/{eventId}/consents",
                "{\"items\":[{\"code\":\"photo\",\"version\":1,\"decision\":\"AGREED\"}]}");
        bodies.put("POST /api/participant/events/{eventId}/upload-intents", json(Map.of("captureRunId", runB, "slotIndex", 1,
                "contentType", "PNG", "fileSize", png.length)));
        bodies.put("POST /api/booth/capture-runs/{captureRunId}/close", "{\"stateVersion\":1}");
        bodies.put("POST /api/booth/capture-runs/{captureRunId}/result-access", "{\"stateVersion\":1}");
    }

    @Test
    @DisplayName("A 행사의 참가자·부스 토큰으로 B 행사의 id 를 넣은 참가·부스 API 전부: 2xx 없음(403·404), B 의 자원 내용 노출 없음")
    void foreignEventIdsWithEventCredentials() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Endpoint endpoint : contract()) {
            String header = endpoint.template().startsWith("/api/booth/") ? BOOTH : PARTICIPANT;
            String token = header.equals(BOOTH) ? boothA : participantA;
            String body = bodies.get(endpoint.key());
            if (endpoint.hasBody() && body == null) {
                violations.add(endpoint.key() + " — 본문 견본이 없다(bodies 에 B 기준 유효 본문을 더할 것)");
                continue;
            }
            var request = mvc.method(HttpMethod.valueOf(endpoint.method().toUpperCase())).uri(resolve(endpoint))
                    .header(header, token);
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            MvcTestResult result = request.exchange();
            checked++;
            int status = result.getResponse().getStatus();
            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (status != 403 && status != 404) {
                violations.add(endpoint.key() + " → " + status + " " + response);
            }
            if (response.contains("B사") || response.contains("https://s3/")) {
                violations.add(endpoint.key() + " — 응답에 B 의 자원 노출: " + response);
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
        assertThat(violations).isEmpty();
    }

    record Endpoint(String method, String template, boolean hasBody, List<String> requiredQuery) {
        String key() {
            return method.toUpperCase() + " " + template;
        }
    }

    // 참가·부스 API 중 경로에 B 의 id 를 넣을 수 있는 것 전부
    private List<Endpoint> contract() throws IOException {
        JsonNode paths = objectMapper.readTree(Files.readString(Path.of("docs/api/openapi.generated.json"))).path("paths");
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (!path.getKey().startsWith("/api/participant/") && !path.getKey().startsWith("/api/booth/")
                    || !path.getKey().contains("{")) {
                continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (METHODS.contains(operation.getKey())) {
                    endpoints.add(new Endpoint(operation.getKey(), path.getKey(), operation.getValue().has("requestBody"),
                            requiredQuery(operation.getValue())));
                }
            }
        }
        // 부스의 회차 닫기는 그 손님 세션을 끝낸다 — 참가 API 를 먼저 부른다(본문 검증용으로 B 자격으로 돌려도 순서가 맞게)
        endpoints.sort(Comparator.comparing((Endpoint endpoint) -> endpoint.template().startsWith("/api/booth/")));
        return endpoints;
    }

    private String resolve(Endpoint endpoint) {
        String template = endpoint.template();
        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder uri = new StringBuilder();
        while (matcher.find()) {
            String value = ids.get(matcher.group(1));
            if (value == null) {
                throw new AssertionError(template + " — 처음 보는 경로 변수 {" + matcher.group(1) + "}: B 쪽 id 를 ids 에 더할 것");
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

    private Live live(String email, String workspaceName, String slug) {
        User owner = userRepository.save(User.localUser(email, "e", workspaceName + " 오너"));
        WorkspaceResponse workspace = workspaceService.createOrganization(owner.getPublicId(), workspaceName);
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(workspace.workspaceId()).orElseThrow().getId());
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest(workspaceName + " 프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, workspace.workspaceId())).frameId();
        Long frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
        EventResponse created = eventService.create(owner.getPublicId(), workspace.workspaceId(),
                new EventSettings(workspaceName + " 행사", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "boothEnabled", true,
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo", "version", 1, "title", "사진 동의", "required", true, "body", "본문"))))));
        eventService.publish(owner.getPublicId(), created.eventId());
        ownerOf.put(created.eventId(), owner.getPublicId());
        return new Live(created.eventId(), slug, frameVersionId);
    }

    private final Map<String, String> ownerOf = new LinkedHashMap<>();

    private String boothToken(Live event) {
        String code = boothDeviceService.create(ownerOf.get(event.eventId()), event.eventId(), "부스").pairingCode();
        return data(mvc.post().uri("/api/public/booth-pairings/redeem").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("code", code))).exchange(), 200).path("boothToken").asString();
    }

    private void consent(String token, Live event) {
        data(post("/api/participant/events/" + event.eventId() + "/consents", token,
                Map.of("items", List.of(Map.of("code", "photo", "version", 1, "decision", "AGREED")))), 200);
    }

    private String uploadIntent(String token, Live event, String runId, int slot) {
        return data(post("/api/participant/events/" + event.eventId() + "/upload-intents", token,
                Map.of("captureRunId", runId, "slotIndex", slot, "contentType", "PNG", "fileSize", png.length)), 201)
                .path("assetId").asString();
    }

    private MvcTestResult post(String uri, String token, Object body) {
        return mvc.post().uri(uri).header(PARTICIPANT, token).contentType(MediaType.APPLICATION_JSON).content(json(body)).exchange();
    }

    private JsonNode data(MvcTestResult result, int status) {
        assertThat(result).hasStatus(status);
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
