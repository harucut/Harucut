package com.harucut.draft;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.draft.dto.DraftCreateRequest;
import com.harucut.draft.dto.DraftResponse;
import com.harucut.draft.dto.DraftUpdateRequest;
import com.harucut.draft.exception.DraftErrorCode;
import com.harucut.draft.repository.FrameDraftAssetRepository;
import com.harucut.draft.repository.FrameDraftRepository;
import com.harucut.draft.service.FrameDraftService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.service.FrameService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 서버 초안의 실제 JPA 경로 — 조건부 UPDATE(revision), 자산 참조 교체, 프레임 삭제 시 초안 정리.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("서버 초안 — 통합")
class FrameDraftIntegrationTest {

    @Autowired private FrameDraftService frameDraftService;
    @Autowired private FrameDraftRepository frameDraftRepository;
    @Autowired private FrameDraftAssetRepository frameDraftAssetRepository;
    @Autowired private FrameService frameService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ObjectMapper objectMapper;

    // 프레임 응답이 presign 을 부른다 — 자격증명 없는 테스트 환경이라 가짜로 둔다
    @MockitoBean
    private FileStorageService fileStorageService;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.localUser("drafter@harucut.com", "encoded", "초안러"));
    }

    @Test
    @DisplayName("만들고(rev 1) → 저장(rev 2) → 옛 revision 저장은 409 DRAFT-001 + 서버 revision")
    void revisionFlow() {
        DraftResponse created = frameDraftService.create(user.getPublicId(),
                new DraftCreateRequest(null, null, doc("v1"), null));
        assertThat(created.revision()).isEqualTo(1);

        DraftResponse saved = frameDraftService.update(user.getPublicId(), created.draftId(),
                new DraftUpdateRequest(1, doc("v2"), null));
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(frameDraftService.get(user.getPublicId(), created.draftId()).document().get("title").asString())
                .isEqualTo("v2");

        assertThatThrownBy(() -> frameDraftService.update(user.getPublicId(), created.draftId(),
                new DraftUpdateRequest(1, doc("stale"), null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(DraftErrorCode.REVISION_CONFLICT);
                    assertThat(((Map<?, ?>) e.getData()).get("revision")).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("assetIds 는 저장할 때마다 통째로 바뀌고, 남의 자산이면 FRAME-005")
    void assetReferences() {
        Asset mine = verifiedAsset(user, "uploads/a.png");
        Asset other = verifiedAsset(userRepository.save(User.localUser("o@harucut.com", "e", "o")), "uploads/b.png");

        DraftResponse created = frameDraftService.create(user.getPublicId(),
                new DraftCreateRequest(null, null, doc("v1"), List.of(mine.getPublicId())));
        assertThat(frameDraftAssetRepository.findAll()).extracting(r -> r.getAssetId()).containsExactly(mine.getId());

        frameDraftService.update(user.getPublicId(), created.draftId(), new DraftUpdateRequest(1, doc("v2"), List.of()));
        assertThat(frameDraftAssetRepository.findAll()).isEmpty();

        assertThatThrownBy(() -> frameDraftService.update(user.getPublicId(), created.draftId(),
                new DraftUpdateRequest(2, doc("v3"), List.of(other.getPublicId()))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.ASSET_NOT_USABLE);
    }

    @Test
    @DisplayName("frameId 로 거르고, 프레임을 지우면 그 초안도 지워진다")
    void frameScopedDrafts() {
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest("f", null,
                root + "frames/p.png", FrameType.CLASSIC, null, null, new BackgroundAttributes.Color("#FFFFFF"),
                null, List.of()));
        frameDraftService.create(user.getPublicId(), new DraftCreateRequest(frame.frameId(), null, doc("edit"), null));
        frameDraftService.create(user.getPublicId(), new DraftCreateRequest(null, null, doc("new"), null));

        assertThat(frameDraftService.list(user.getPublicId(), String.valueOf(frame.frameId()))).hasSize(1);
        assertThat(frameDraftService.list(user.getPublicId(), "new")).hasSize(1);
        assertThat(frameDraftService.list(user.getPublicId(), null)).hasSize(2);

        frameService.deleteFrame(user.getPublicId(), frame.frameId());

        assertThat(frameDraftRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("남의 초안은 404 다")
    void othersDraftHidden() {
        DraftResponse created = frameDraftService.create(user.getPublicId(),
                new DraftCreateRequest(null, null, doc("v1"), null));
        User other = userRepository.save(User.localUser("x@harucut.com", "e", "x"));

        assertThatThrownBy(() -> frameDraftService.get(other.getPublicId(), created.draftId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("256KB 를 넘는 문서는 413 DRAFT-002")
    void documentTooLarge() {
        JsonNode huge = objectMapper.valueToTree(Map.of("blob", "x".repeat(262_145)));

        assertThatThrownBy(() -> frameDraftService.create(user.getPublicId(),
                new DraftCreateRequest(null, null, huge, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DraftErrorCode.DOCUMENT_TOO_LARGE);
    }

    private JsonNode doc(String title) {
        return objectMapper.valueToTree(Map.of("title", title, "layers", List.of()));
    }

    private Asset verifiedAsset(User owner, String key) {
        Long workspaceId = workspaceService.personalWorkspaceOf(owner.getId()).getId();
        Asset asset = Asset.pendingUpload(Asset.newPublicId(), workspaceId, AssetPurpose.FRAME_ASSET, key,
                "image/png", 1L, owner.getId());
        asset.verify(1L, 1, 1, LocalDateTime.of(2026, 10, 26, 10, 0));
        return assetRepository.save(asset);
    }
}
