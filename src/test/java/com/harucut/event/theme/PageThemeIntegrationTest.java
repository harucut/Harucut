package com.harucut.event.theme;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.dto.PublicEventResponse;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PublicEventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P2B-4 — 페이지 테마: 설정 → 게시 검사(위반 모음) → 공개 응답(기본값 채움·자산 URL)을 실제 JPA 로 본다(ADR-0008 §5).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("페이지 테마 — 통합")
class PageThemeIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private PublicEventService publicEventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private WorkspaceResponse org;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString())).willAnswer(i -> "https://s3/" + i.getArgument(0));
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
    }

    @Test
    @DisplayName("고친 색만 바뀌고 나머지는 기본값, 자산은 URL, 문구는 그대로, 하루컷 표시는 기본으로 보인다")
    void resolvedTheme() {
        Asset logo = themeAsset(workspaceId(), "logo.png");
        EventResponse event = publishable("theme-ok", Map.of(
                "schemaVersion", 1,
                "colors", Map.of("bg", "#FFF8F3", "accent", "#C8553D"),
                "font", "PRETENDARD",
                "logoAssetId", logo.getPublicId(),
                "copy", Map.of("welcome", "민지 ♥ 준호 결혼식에 오신 것을 환영해요")));

        eventService.publish(owner.getPublicId(), event.eventId());
        PublicEventResponse view = publicEventService.getBySlug("theme-ok");

        assertThat(view.theme().colors().bg()).isEqualTo("#FFF8F3");
        assertThat(view.theme().colors().accent()).isEqualTo("#C8553D");
        assertThat(view.theme().colors().card()).isNotBlank();       // 기본값
        assertThat(view.theme().font()).isEqualTo("PRETENDARD");
        assertThat(view.theme().logoUrl()).isEqualTo("https://s3/" + logo.getS3Key());
        assertThat(view.theme().backgroundUrl()).isNull();
        assertThat(view.theme().copy().welcome()).startsWith("민지");
        assertThat(view.theme().branding().hideHarucutBranding()).isFalse();
        assertThat(view.theme().branding().outputLogo()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("테마가 없어도 기본값으로 채워 내려준다")
    void defaultTheme() {
        EventResponse event = publishable("theme-none", null);
        eventService.publish(owner.getPublicId(), event.eventId());

        PublicEventResponse view = publicEventService.getBySlug("theme-none");
        assertThat(view.theme().font()).isEqualTo("SYSTEM");
        assertThat(view.theme().colors().bg()).isNotBlank();
    }

    @Test
    @DisplayName("게시 위반: 색 형식·글꼴·문구 길이·남의 공간 자산·이용권 없는 하루컷 표시 숨김·출력 로고 값")
    void violations() {
        User other = userRepository.save(User.localUser("other@harucut.com", "e", "남"));
        WorkspaceResponse otherOrg = workspaceService.createOrganization(other.getPublicId(), "다른 곳");
        Asset foreign = themeAsset(workspaceRepository.findByPublicId(otherOrg.workspaceId()).orElseThrow().getId(), "x.png");
        EventResponse event = publishable("theme-bad", Map.of(
                "colors", Map.of("bg", "red"),
                "font", "COMIC_SANS",
                "backgroundAssetId", foreign.getPublicId(),
                "copy", Map.of("done", "가".repeat(201)),
                "branding", Map.of("hideHarucutBranding", true, "outputLogo", "WATERMARK")));

        assertThatThrownBy(() -> eventService.publish(owner.getPublicId(), event.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(EventErrorCode.PUBLISH_INVALID);
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field")).contains(
                            "theme.colors.bg", "theme.font", "theme.backgroundAssetId", "theme.copy.done",
                            "theme.branding.hideHarucutBranding", "theme.branding.outputLogo");
                });
    }

    // ── helpers ──────────────────────────────

    private EventResponse publishable(String slug, Map<String, Object> theme) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null));
        Map<String, Object> patch = new HashMap<>(Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))));
        if (theme != null) {
            patch.put("theme", theme);
        }
        return eventService.patch(owner.getPublicId(), created.eventId(),
                new EventPatchRequest(created.settingsVersion(), objectMapper.valueToTree(patch)));
    }

    private Asset themeAsset(Long workspaceId, String name) {
        Asset asset = Asset.pendingUpload(Asset.newPublicId(), workspaceId, AssetPurpose.PAGE_THEME_ASSET,
                S3Keys.userRoot(owner.getPublicId()) + "assets/" + name, "image/png", 100, owner.getId());
        asset.verify(100, 10, 10, LocalDateTime.now());
        return assetRepository.save(asset);
    }

    private Long workspaceId() {
        return workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
    }
}
