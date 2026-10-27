package com.harucut.frame.cell;

import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.RateLimiter;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.frame.version.FrameSpecComposer;
import com.harucut.frame.version.FrameSpecV2;
import com.harucut.media.compose.ComposeSpec;
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
import org.springframework.http.HttpHeaders;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 자유 사진 칸 계약 (ADR-0012) — 프레임 저장·응답·검증, 발행본 layout.cells, 렌더 스위치가 꺼진 동안의 게시·개인 합성 409 FRAME-008.
 * 실제 보안 체인·컨트롤러·JPA. S3 만 가짜다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("자유 사진 칸 — 통합")
class FreeCellsIntegrationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private EventService eventService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameSpecComposer frameSpecComposer;
    @Autowired private FrameVersionRepository frameVersionRepository;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User designer;
    private String workspace;

    @BeforeEach
    void setUp() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] png = out.toByteArray();
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/get");

        designer = userRepository.save(User.localUser("cells@harucut.com", "e", "디자이너"));
        WorkspaceResponse org = workspaceService.createOrganization(designer.getPublicId(), "자유칸");
        workspace = org.workspaceId();
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(workspace).orElseThrow().getId());
    }

    @Test
    @DisplayName("저장: cells 가 응답에 그대로, 발행본은 layout.cells·칸 사각형 slots·shotIndex 순 slotBindings — PUT 에서 빼면 고정 레이아웃")
    void saveAndPublish() {
        JsonNode created = data(post("/api/auth/user/frame", frame(workspace, PhotoCellsTest.cells())), 200);
        long frameId = created.path("frameId").asLong();
        assertThat(created.path("cells")).hasSize(4);
        assertThat(created.path("cells").get(0).path("rotation").asDouble()).isEqualTo(-4.5);
        assertThat(created.path("cells").get(1).path("shape").path("type").asString()).isEqualTo("ELLIPSE");
        assertThat(created.path("cells").get(0).path("shotIndex").asInt()).isEqualTo(3);

        FrameVersionResponse version = frameVersionService.publish(designer.getPublicId(), frameId);
        FrameSpecV2 spec = version.spec();
        assertThat(spec.layout().cells()).hasSize(4);
        assertThat(spec.layout().cells().get(0).shotIndex()).isEqualTo(3);
        assertThat(spec.layout().cells().get(0).rotation()).isEqualTo(-4.5);
        assertThat(spec.layout().slots().get(0)).isEqualTo(new FrameSpecV2.Slot("s1", 150, 200, 1700, 1200));
        assertThat(spec.slotBindings()).extracting(FrameSpecV2.SlotBinding::sourceIndex).containsExactly(3, 2, 1, 0);
        // 행사 합성의 스냅샷(발행본 → ComposeSpec)에도 칸마다의 회전·모양·순번이 실린다(ADR-0012 §4)
        ComposeSpec composed = frameSpecComposer.compose(frameVersionRepository.findById(version.frameVersionId()).orElseThrow());
        assertThat(composed.cells()).extracting(ComposeSpec.Cell::shotIndex).containsExactly(3, 2, 1, 0);
        assertThat(composed.cells().get(1).shape()).isEqualTo(ComposeSpec.CellShape.ELLIPSE);
        assertThat(composed.cells().get(0).cornerRadius()).isEqualTo(40);
        assertThat(composed.slots().get(0)).isEqualTo(new FrameLayout.Slot(150, 200, 1700, 1200));

        Map<String, Object> fixed = frame(workspace, null);
        fixed.put("version", created.path("version").asLong());
        JsonNode updated = data(put("/api/auth/user/frame/" + frameId, fixed), 200);
        assertThat(updated.has("cells")).isFalse();
        FrameSpecV2 fixedSpec = frameVersionService.publish(designer.getPublicId(), frameId).spec();
        assertThat(fixedSpec.layout().cells()).isNull();
        assertThat(fixedSpec.slotBindings()).extracting(FrameSpecV2.SlotBinding::sourceIndex).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("검증 위반은 400 GEN-002 + data.violations(칸 수·shotIndex 중복·이미지 마스크·캔버스 밖)")
    void rejectsInvalidCells() {
        List<Map<String, Object>> bad = List.of(
                cell(-10, 200, 0, "RECT", 0), cell(150, 1480, 0, "RECT", 0),
                cell(150, 2760, 0, "MASK", 2));
        MvcTestResult result = post("/api/auth/user/frame", frame(workspace, bad));
        assertThat(result).hasStatus(400);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("code").asString()).isEqualTo("GEN-002");
        assertThat(body.path("data").path("violations").findValuesAsString("field"))
                .contains("cells", "cells[0]", "cells[1].shotIndex", "cells[2].shape");
    }

    @Test
    @DisplayName("렌더 스위치가 꺼져 있으면: 자유 칸 발행본을 쓰는 행사 게시 409 FRAME-008(고정 레이아웃은 게시된다), 자유 칸 프레임의 개인 합성 409 FRAME-008")
    void blockedUntilRenderable() {
        long freeFrame = data(post("/api/auth/user/frame", frame(workspace, PhotoCellsTest.cells())), 200).path("frameId").asLong();
        long fixedFrame = data(post("/api/auth/user/frame", frame(workspace, null)), 200).path("frameId").asLong();
        Long freeVersion = frameVersionService.publish(designer.getPublicId(), freeFrame).frameVersionId();
        Long fixedVersion = frameVersionService.publish(designer.getPublicId(), fixedFrame).frameVersionId();

        String freeEvent = event("free-cells-event", freeVersion);
        assertThat(mvc.post().uri("/api/auth/events/" + freeEvent + "/publish").header(HttpHeaders.AUTHORIZATION, bearer()).exchange())
                .hasStatus(409).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("FRAME-008"));
        String fixedEvent = event("fixed-cells-event", fixedVersion);
        assertThat(mvc.post().uri("/api/auth/events/" + fixedEvent + "/publish").header(HttpHeaders.AUTHORIZATION, bearer()).exchange())
                .hasStatusOk();

        long personal = data(post("/api/auth/user/frame", frame(null, PhotoCellsTest.cells())), 200).path("frameId").asLong();
        String root = S3Keys.userRoot(designer.getPublicId());
        assertThat(post("/api/auth/user/media/compose", Map.of("frameId", personal, "idempotencyKey", "free-1",
                "sourceKeys", List.of(root + "fourcuts/sources/1.png", root + "fourcuts/sources/2.png",
                        root + "fourcuts/sources/3.png", root + "fourcuts/sources/4.png"))))
                .hasStatus(409).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("FRAME-008"));
    }

    private String event(String slug, Long frameVersionId) {
        EventResponse created = eventService.create(designer.getPublicId(), workspace,
                new EventSettings(slug, slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(designer.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "photo", "version", 1, "title", "사진 동의", "required", true, "body", "본문"))))));
        return created.eventId();
    }

    private Map<String, Object> frame(String workspaceId, Object cells) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "자유 칸");
        body.put("previewKey", S3Keys.userRoot(designer.getPublicId()) + "frames/p.png");
        body.put("frameType", "CLASSIC");
        body.put("background", Map.of("type", "COLOR", "value", "#FFFFFF"));
        body.put("components", List.of());
        if (workspaceId != null) {
            body.put("workspaceId", workspaceId);
        }
        if (cells != null) {
            body.put("cells", cells);
        }
        return body;
    }

    private static Map<String, Object> cell(int x, int y, double rotation, String shape, int shotIndex) {
        return Map.of("x", x, "y", y, "width", 1700, "height", 1200, "rotation", rotation,
                "shape", "MASK".equals(shape) ? Map.of("type", shape, "maskAssetId", "Ab12") : Map.of("type", shape), "shotIndex", shotIndex);
    }

    private MvcTestResult post(String uri, Object body) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)).exchange();
    }

    private MvcTestResult put(String uri, Object body) {
        return mvc.put().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)).exchange();
    }

    private String bearer() {
        return "Bearer " + jwtTokenService.createAccessToken(designer.getPublicId(), designer.getUserRole(), designer.getUserStatus()).value();
    }

    private JsonNode data(MvcTestResult result, int status) {
        assertThat(result).hasStatus(status);
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}
