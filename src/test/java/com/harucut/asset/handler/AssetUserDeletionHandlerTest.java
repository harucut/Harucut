package com.harucut.asset.handler;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetUserDeletionHandler — 탈퇴할 때 개인 공간 밖에서 쓰는 자산")
class AssetUserDeletionHandlerTest {

    private static final Long USER_ID = 3L;
    private static final Long PERSONAL_ID = 10L;

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AssetRepository assetRepository;
    @InjectMocks private AssetUserDeletionHandler handler;

    @Test
    @DisplayName("쓰는 곳이 있으면 가장 먼저 만든 조직 공간으로, 시스템 프레임만 쓰면 시스템 범위(NULL)로 옮기고, 아무도 안 쓰면 두다(마지막 단계가 지운다)")
    void movesOnlyAssetsUsedOutside() {
        givenPersonalWorkspace();
        Asset usedByOrgs = asset(1L, "org.png");
        Asset usedBySystem = asset(2L, "system.png");
        Asset unused = asset(3L, "unused.png");
        given(assetRepository.findAllByWorkspaceIdOrderByIdAsc(PERSONAL_ID)).willReturn(List.of(usedByOrgs, usedBySystem, unused));
        given(assetRepository.findWorkspacesUsingOutside(eq(1L), eq(usedByOrgs.getS3Key()), eq("%" + usedByOrgs.getS3Key() + "%"),
                eq(PERSONAL_ID), eq(USER_ID))).willReturn(Arrays.asList(31L, 20L, null));
        given(assetRepository.findWorkspacesUsingOutside(eq(2L), eq(usedBySystem.getS3Key()), eq("%" + usedBySystem.getS3Key() + "%"),
                eq(PERSONAL_ID), eq(USER_ID))).willReturn(Arrays.asList((Long) null));
        given(assetRepository.findWorkspacesUsingOutside(eq(3L), eq(unused.getS3Key()), eq("%" + unused.getS3Key() + "%"),
                eq(PERSONAL_ID), eq(USER_ID))).willReturn(List.of());

        handler.handleUserDeletion(USER_ID);

        then(assetRepository).should().moveToWorkspace(1L, 20L);
        then(assetRepository).should().moveToWorkspace(2L, null);
        then(assetRepository).should(never()).moveToWorkspace(eq(3L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("개인 공간이 없으면 아무것도 하지 않는다")
    void noPersonalWorkspace() {
        given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.empty());

        handler.handleUserDeletion(USER_ID);

        then(assetRepository).shouldHaveNoInteractions();
    }

    private void givenPersonalWorkspace() {
        Workspace personal = mock(Workspace.class);
        given(personal.getId()).willReturn(PERSONAL_ID);
        given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.of(personal));
    }

    private static Asset asset(Long id, String name) {
        Asset asset = Asset.pendingUpload(Asset.newPublicId(), PERSONAL_ID, AssetPurpose.FRAME_ASSET,
                "uploads/users/leaver/assets/" + name, "image/png", 100, USER_ID);
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }
}
