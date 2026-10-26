package com.harucut.frame.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 프레임이 참조하는 자산(assetId·renderedAssetId·배경 assetId)을 실제 key 로 바꾸면서 쓸 수 있는지 본다.
 * 쓸 수 없으면 400 FRAME-005 — 남의 공간, 미검증, 용도 불일치. (ADR-0006 §3)
 *
 * <p>owner 가 null 이면 시스템 프레임이다. 공간 검사를 건너뛰고(ROLE_ADMIN 이 신뢰 경계) 검증 상태·용도만 본다.
 */
@Component
@RequiredArgsConstructor
public class FrameAssetResolver {

    private final AssetRepository assetRepository;
    private final WorkspaceService workspaceService;

    public Asset require(String assetPublicId, AssetPurpose purpose, Long ownerUserId) {
        Asset asset = assetRepository.findByPublicId(assetPublicId)
                .orElseThrow(() -> notUsable("없는 자산: " + assetPublicId));
        checkUsable(asset, purpose, ownerUserId);
        return asset;
    }

    /** 초안의 assetIds — 용도는 묻지 않는다(편집기 문서가 배경·스티커·글자 무엇이든 쓴다). 검증·소유만 본다 */
    public Asset requireForDraft(String assetPublicId, Long ownerUserId) {
        Asset asset = assetRepository.findByPublicId(assetPublicId)
                .orElseThrow(() -> notUsable("없는 자산: " + assetPublicId));
        if (!asset.isVerified()) {
            throw notUsable("미검증 자산: " + assetPublicId);
        }
        if (!ownerUserId.equals(asset.getUploadedByUserId())
                && !workspaceService.isMember(asset.getWorkspaceId(), ownerUserId)) {
            throw notUsable("남의 자산: " + assetPublicId);
        }
        return asset;
    }

    /**
     * renderedKey 를 key 문자열로 받았을 때: 그 key 가 자산이면 자산으로 받은 것과 같은 검사를 한다.
     * 자산 행이 없으면 예전 경로(FRAME_COMPONENT 업로드)라 기존 소유권 검사에 맡긴다.
     */
    public Optional<Asset> findByKey(String key, AssetPurpose purpose, Long ownerUserId) {
        Optional<Asset> asset = assetRepository.findByS3Key(key);
        asset.ifPresent(found -> checkUsable(found, purpose, ownerUserId));
        return asset;
    }

    public java.util.Set<String> assetBackedKeys(Collection<String> keys) {
        return keys.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(assetRepository.findExistingKeys(keys));
    }

    // 응답 조립용: 내부 id → public id
    public Map<Long, String> publicIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return assetRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Asset::getId, Asset::getPublicId, (a, b) -> a));
    }

    public Map<Long, Asset> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return assetRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (a, b) -> a));
    }

    private void checkUsable(Asset asset, AssetPurpose purpose, Long ownerUserId) {
        if (!asset.isVerified()) {
            throw notUsable("미검증 자산: " + asset.getPublicId());
        }
        if (asset.getPurpose() != purpose) {
            throw notUsable("용도 불일치: " + asset.getPurpose() + " ≠ " + purpose);
        }
        if (ownerUserId != null
                && !ownerUserId.equals(asset.getUploadedByUserId())
                && !workspaceService.isMember(asset.getWorkspaceId(), ownerUserId)) {
            throw notUsable("남의 자산: " + asset.getPublicId());
        }
    }

    private static BusinessException notUsable(String detail) {
        return new BusinessException(FrameErrorCode.ASSET_NOT_USABLE, detail);
    }
}
