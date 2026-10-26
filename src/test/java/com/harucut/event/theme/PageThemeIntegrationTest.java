package com.harucut.event.theme;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.dto.PageTheme;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
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
    @Autowired private EntitlementRepository entitlementRepository;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private WorkspaceResponse org;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString())).willAnswer(i -> "https://s3/" + i.getArgument(0));
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        // 디자인 기능만 있고 하루컷 표시 숨김(WHITE_LABEL)은 없는 이용권
        TestEntitlements.grant(entitlementRepository, workspaceId(), 100, EnumSet.of(Feature.CUSTOM_THEME));
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
        assertThat(view.theme().colors().card()).isEqualTo("#FFFFFF");   // 기본값(FE .ev-surface 와 같다)
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
        assertThat(view.theme().colors()).isEqualTo(new PageTheme.Colors(
                "#F6F6F4", "#FFFFFF", "#1B1B1B", "#5E5E5E", "#DADAD8", "#1B1B1B", "#FFFFFF"));
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

    @Test
    @DisplayName("CUSTOM_THEME 이 없으면 디자인(색·글꼴·로고·배경)은 게시 위반, 문구만이면 게시된다")
    void customThemeFeature() {
        User plain = userRepository.save(User.localUser("plain@harucut.com", "e", "기본"));
        WorkspaceResponse plainOrg = workspaceService.createOrganization(plain.getPublicId(), "기본 계약");
        TestEntitlements.grant(entitlementRepository,
                workspaceRepository.findByPublicId(plainOrg.workspaceId()).orElseThrow().getId(), 100, EnumSet.noneOf(Feature.class));
        Long plainFrame = frameVersionService.publish(plain.getPublicId(), frameService.createFrame(plain.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(plain.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, plainOrg.workspaceId())).frameId())
                .frameVersionId();

        EventResponse designed = publishable(plain, plainOrg, plainFrame, "plain-designed", Map.of("font", "PRETENDARD"));
        assertThatThrownBy(() -> eventService.publish(plain.getPublicId(), designed.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field")).containsExactly("theme");
                });

        // 빈 colors 와 SYSTEM(바꾸지 않음) 글꼴은 디자인이 아니다 — 문구와 함께 게시된다
        EventResponse copyOnly = publishable(plain, plainOrg, plainFrame, "plain-copy", Map.of("copy", Map.of("welcome", "어서 오세요"),
                "colors", Map.of(), "font", "SYSTEM"));
        eventService.publish(plain.getPublicId(), copyOnly.eventId());
        assertThat(publicEventService.getBySlug("plain-copy").theme().copy().welcome()).isEqualTo("어서 오세요");
    }

    // ── helpers ──────────────────────────────

    private EventResponse publishable(String slug, Map<String, Object> theme) {
        return publishable(owner, org, frameVersionId, slug, theme);
    }

    private EventResponse publishable(User owner, WorkspaceResponse org, Long frameVersionId, String slug, Map<String, Object> theme) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null, null));
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
