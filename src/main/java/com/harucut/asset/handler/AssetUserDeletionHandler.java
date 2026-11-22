package com.harucut.asset.handler;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.auth.service.UserDeletionHandler;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 탈퇴 — 개인 공간 밖(조직·시스템 프레임)에서 아직 쓰는 개인 자산은 지우지 않고 그 공간으로 옮긴다.
 * 올린 사람은 자기 자산을 어느 공간 프레임에든 넣을 수 있고(FrameAssetResolver.checkUsable) 조직 프레임은 탈퇴 뒤에도 남는다.
 * 지우면 컴포넌트·발행본 FK 가 탈퇴를 매번 되돌리고, FK 없는 배경 key 참조는 조직 프레임 이미지를 조용히 깨뜨린다.
 * 쓰는 곳이 여럿이면 가장 먼저 만든 조직 공간, 시스템 프레임만 쓰면 시스템 범위(NULL)로 옮긴다.
 * 남은 개인 자산은 탈퇴 마지막 단계(WorkspaceService.deleteForUser)가 지운다. 개인 공간 프레임의 참조는 세지 않으므로 다른 핸들러와 순서가 상관없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetUserDeletionHandler implements UserDeletionHandler {

    private final WorkspaceRepository workspaceRepository;
    private final AssetRepository assetRepository;

    @Transactional
    @Override
    public void handleUserDeletion(Long userId) {
        Long personalId = workspaceRepository.findByPersonalOwnerUserId(userId).map(Workspace::getId).orElse(null);
        if (personalId == null) {
            return;
        }
        for (Asset asset : assetRepository.findAllByWorkspaceId(personalId)) {
            List<Long> users = assetRepository.findWorkspacesUsingOutside(asset.getId(), asset.getS3Key(), personalId);
            if (users.isEmpty()) {
                continue;
            }
            Long target = users.stream().filter(Objects::nonNull).min(Long::compare).orElse(null);
            assetRepository.moveToWorkspace(asset.getId(), target);
            log.info("[탈퇴] 개인 공간 밖에서 쓰는 자산을 옮겨 남긴다: asset={} → workspace={}", asset.getPublicId(), target);
        }
    }
}
