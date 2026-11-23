package com.harucut.frame.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FrameAssetResolver — 프레임이 쓸 수 있는 자산인가 (FRAME-005)")
class FrameAssetResolverTest {

    private static final Long OWNER = 1L;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private FrameAssetResolver resolver;

    @Test
    @DisplayName("내가 올린 VERIFIED·같은 용도 자산은 통과한다")
    void ownVerifiedAsset() {
        Asset asset = asset(AssetPurpose.FRAME_ASSET, OWNER, true);
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset));

        assertThat(resolver.require("As1", AssetPurpose.FRAME_ASSET, OWNER)).isSameAs(asset);
    }

    @Test
    @DisplayName("같은 공간 멤버가 올린 자산도 통과한다")
    void workspaceMemberAsset() {
        Asset asset = asset(AssetPurpose.FRAME_ASSET, 2L, true);
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset));
        given(workspaceService.isMember(10L, OWNER)).willReturn(true);

        assertThat(resolver.require("As1", AssetPurpose.FRAME_ASSET, OWNER)).isSameAs(asset);
    }

    @Test
    @DisplayName("남의 공간 자산은 FRAME-005")
    void foreignAsset() {
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset(AssetPurpose.FRAME_ASSET, 2L, true)));
        given(workspaceService.isMember(10L, OWNER)).willReturn(false);

        assertNotUsable(() -> resolver.require("As1", AssetPurpose.FRAME_ASSET, OWNER));
    }

    @Test
    @DisplayName("미검증 자산은 FRAME-005")
    void unverifiedAsset() {
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset(AssetPurpose.FRAME_ASSET, OWNER, false)));

        assertNotUsable(() -> resolver.require("As1", AssetPurpose.FRAME_ASSET, OWNER));
    }

    @Test
    @DisplayName("용도가 다르면 FRAME-005 — FRAME_ASSET 을 TEXT_RENDER 자리에")
    void purposeMismatch() {
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset(AssetPurpose.FRAME_ASSET, OWNER, true)));

        assertNotUsable(() -> resolver.require("As1", AssetPurpose.TEXT_RENDER, OWNER));
    }

    @Test
    @DisplayName("없는 자산은 FRAME-005")
    void unknownAsset() {
        given(assetRepository.findByPublicId("Nope")).willReturn(Optional.empty());

        assertNotUsable(() -> resolver.require("Nope", AssetPurpose.FRAME_ASSET, OWNER));
    }

    @Test
    @DisplayName("시스템 프레임(owner null)은 공간 검사를 건너뛰고 검증·용도만 본다")
    void systemFrameSkipsOwnership() {
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(asset(AssetPurpose.FRAME_ASSET, 2L, true)));

        assertThat(resolver.require("As1", AssetPurpose.FRAME_ASSET, null)).isNotNull();
    }

    @Test
    @DisplayName("key 로 찾은 자산이 없으면 empty — 예전 업로드 경로는 기존 검사에 맡긴다")
    void legacyKeyHasNoAsset() {
        given(assetRepository.findByS3Key("uploads/legacy.png")).willReturn(Optional.empty());

        assertThat(resolver.findByKey("uploads/legacy.png", AssetPurpose.TEXT_RENDER, OWNER)).isEmpty();
    }

    private static Asset asset(AssetPurpose purpose, Long uploadedBy, boolean verified) {
        Asset asset = Asset.pendingUpload("As1", 10L, purpose, "uploads/a.png", "image/png", 1L, uploadedBy);
        if (verified) {
            asset.verify(1L, 1, 1, LocalDateTime.of(2026, 10, 26, 10, 0));
        }
        return asset;
    }

    private static void assertNotUsable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.ASSET_NOT_USABLE);
    }
}
