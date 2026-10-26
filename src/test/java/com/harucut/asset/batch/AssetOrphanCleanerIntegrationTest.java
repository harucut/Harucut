package com.harucut.asset.batch;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;

/*
 * 미참조 자산 정리 — 실제 JPA. 참조(id·key 문자열) 판정과 유예·용도 규칙을 본다.
 * 트랜잭션 테스트라 커밋 후 S3 삭제는 일어나지 않고, 예약(deleteAfterCommit)에 넘긴 key 로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("미참조 자산 정리 — 통합")
class AssetOrphanCleanerIntegrationTest {

    @Autowired private AssetOrphanCleaner cleaner;
    @Autowired private AssetRepository assetRepository;
    @Autowired private FrameService frameService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private User user;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.localUser("u@harucut.com", "e", "u"));
        workspaceId = workspaceService.personalWorkspaceOf(user.getId()).getId();
    }

    @Test
    @DisplayName("유예가 지난 미참조 자산만 지운다 — 참조(id·배경 key·컴포넌트 key·프리뷰)·유예 안·규칙 없는 용도는 남긴다")
    void deletesOnlyOldUnreferenced() {
        Asset orphan = verified(AssetPurpose.FRAME_ASSET, "orphan.png");
        Asset orphanText = verified(AssetPurpose.TEXT_RENDER, "text.png");
        Asset abandoned = pending(AssetPurpose.PROFILE, "abandoned.png");
        Asset rejected = rejected(AssetPurpose.FRAME_ASSET, "rejected.png");
        Asset profile = verified(AssetPurpose.PROFILE, "profile.png");      // 참조 규칙 없음 — 남긴다
        Asset recent = verified(AssetPurpose.FRAME_ASSET, "recent.png");    // 유예 안 — 남긴다
        Asset byId = verified(AssetPurpose.FRAME_ASSET, "by-id.png");
        Asset bgKey = verified(AssetPurpose.FRAME_ASSET, "bg.png");
        Asset sourceKey = rejected(AssetPurpose.FRAME_ASSET, "legacy.png");  // 거부됐어도 프레임이 key 로 쓰면 남긴다
        Asset previewKey = verified(AssetPurpose.FRAME_ASSET, "preview.png");

        frameService.createFrame(user.getPublicId(), new FrameCreateRequest("프레임", null, previewKey.getS3Key(),
                FrameType.CLASSIC, null, null, new BackgroundAttributes.Image(bgKey.getS3Key(), 1.0, null), null,
                List.of(component(null, byId.getPublicId()), component(sourceKey.getS3Key(), null)), null, null));
        entityManager.flush();
        age(orphan, orphanText, abandoned, rejected, profile, byId, bgKey, sourceKey, previewKey);

        int deleted = cleaner.run();

        assertThat(deleted).isEqualTo(4);
        assertThat(remainingKeys()).contains(profile.getS3Key(), recent.getS3Key(), byId.getS3Key(), bgKey.getS3Key(),
                sourceKey.getS3Key(), previewKey.getS3Key())
                .doesNotContain(orphan.getS3Key(), orphanText.getS3Key(), abandoned.getS3Key(), rejected.getS3Key());
        assertThat(scheduledForDeletion())
                .containsExactlyInAnyOrder(orphan.getS3Key(), orphanText.getS3Key(), abandoned.getS3Key(), rejected.getS3Key());
    }

    @Test
    @DisplayName("촬영 입력: 합성에 묶이지 않은 것만 지운다 — 묶인 입력은 결과의 원본이다")
    void captureInputs() {
        Asset unbound = captureInput("unbound.png");
        Asset bound = captureInput("bound.png");
        entityManager.flush();
        jdbcTemplate.update("update asset set bound_compose_job_id = 999 where asset_id = ?", bound.getId());
        age(unbound, bound);

        assertThat(cleaner.run()).isEqualTo(1);
        assertThat(remainingKeys()).contains(bound.getS3Key()).doesNotContain(unbound.getS3Key());
    }

    // ── fixtures ──────────────────────────────

    private Asset verified(AssetPurpose purpose, String name) {
        Asset asset = pending(purpose, name);
        asset.verify(100, 10, 10, LocalDateTime.now());
        return asset;
    }

    private Asset rejected(AssetPurpose purpose, String name) {
        Asset asset = pending(purpose, name);
        asset.reject("ASSET-003");
        return asset;
    }

    private Asset pending(AssetPurpose purpose, String name) {
        return assetRepository.save(Asset.pendingUpload(Asset.newPublicId(), workspaceId, purpose,
                S3Keys.userRoot(user.getPublicId()) + "assets/" + name, "image/png", 100, user.getId()));
    }

    private Asset captureInput(String name) {
        Asset asset = assetRepository.save(Asset.captureInput(Asset.newPublicId(), 1L, 1L, 1L, 0,
                "uploads/events/e/runs/r/inputs/" + name, "image/png", 100));
        asset.verify(100, 10, 10, LocalDateTime.now());
        return asset;
    }

    private static FrameCreateRequest.ComponentRequest component(String source, String assetId) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.PHOTO, source == null ? "x" : source, null,
                0.0, 0.0, 10.0, 10.0, 1.0, 0.0, 0, null, assetId, null, null);
    }

    // 유예(7일)보다 오래된 자산으로 만든다
    private void age(Asset... assets) {
        entityManager.flush();
        for (Asset asset : assets) {
            jdbcTemplate.update("update asset set created_at = ? where asset_id = ?",
                    LocalDateTime.now().minusDays(8), asset.getId());
        }
        entityManager.clear();
    }

    private List<String> remainingKeys() {
        return assetRepository.findAll().stream().map(Asset::getS3Key).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> scheduledForDeletion() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        then(s3Deleter).should(atLeast(0)).deleteAfterCommit(captor.capture());
        List<String> keys = new ArrayList<>();
        captor.getAllValues().forEach(keys::addAll);
        return keys;
    }
}
