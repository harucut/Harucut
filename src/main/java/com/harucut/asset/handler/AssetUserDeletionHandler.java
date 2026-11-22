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
 *
 * <p>올린 사람은 자기 자산을 어느 공간의 프레임에든 넣을 수 있다(FrameAssetResolver.checkUsable — 공간과 상관없이 본인 업로드).
 * 조직 프레임은 탈퇴 뒤에도 남는다(FrameUserDeletionHandler). 그 자산을 지우면 두 가지가 깨진다:
 * <ul>
 *   <li>프레임 컴포넌트·발행본의 FK 가 탈퇴 트랜잭션을 매번 되돌린다. 배치가 재시도할 때마다 실패한다.</li>
 *   <li>FK 없는 key 참조(배경 JSON 등)는 조직 프레임의 이미지를 조용히 깨뜨린다.</li>
 * </ul>
 * 옮긴 자산은 그 공간 멤버가 계속 편집에 쓸 수 있다. 쓰는 곳이 여럿이면 가장 먼저 만든 조직 공간으로 옮기고,
 * 시스템 프레임만 쓰면 시스템 범위(NULL)로 옮긴다.
 *
 * <p>남은 개인 자산은 탈퇴 마지막 단계(WorkspaceService.deleteForUser)가 지운다. 이 핸들러는 그보다 먼저 돈다.
 * 다른 핸들러와의 순서는 상관없다 — 개인 공간 프레임과 본인 초안의 참조는 세지 않는다(그 핸들러들이 지운다).
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
        for (Asset asset : assetRepository.findAllByWorkspaceIdOrderByIdAsc(personalId)) {
            List<Long> users = assetRepository.findWorkspacesUsingOutside(asset.getId(), asset.getS3Key(),
                    "%" + asset.getS3Key() + "%", personalId, userId);
            if (users.isEmpty()) {
                continue;
            }
            Long target = users.stream().filter(Objects::nonNull).min(Long::compare).orElse(null);
            assetRepository.moveToWorkspace(asset.getId(), target);
            log.info("[탈퇴] 개인 공간 밖에서 쓰는 자산을 옮겨 남긴다: asset={} → workspace={}", asset.getPublicId(), target);
        }
    }
}
