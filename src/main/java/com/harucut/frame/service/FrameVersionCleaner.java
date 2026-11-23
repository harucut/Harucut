package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameVersionAssetRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 프레임을 지울 때 그 발행본과 발행본의 자산 참조를 먼저 지운다(FK). 자산 자체는 지우지 않는다 —
 * 다른 프레임·초안이 참조할 수 있어 미참조 자산 정리 배치가 센다.
 * 행사 경험이 제공 중인 발행본(event_frame)이 있으면 409 FRAME-006 으로 막는다.
 */
@Component
@RequiredArgsConstructor
public class FrameVersionCleaner {

    private final FrameVersionRepository frameVersionRepository;
    private final FrameVersionAssetRepository frameVersionAssetRepository;

    public void deleteForFrames(Collection<Long> frameIds) {
        if (frameIds.isEmpty()) {
            return;
        }
        List<Long> versionIds = frameVersionRepository.findIdsByFrameIdIn(frameIds);
        if (versionIds.isEmpty()) {
            return;
        }
        // 행사가 게시한 경험이 이 발행본을 제공 중이다 — 지우면 참가자 화면과 합성이 깨진다
        if (frameVersionRepository.countUsedByEvents(versionIds) > 0) {
            throw new BusinessException(FrameErrorCode.VERSION_IN_USE);
        }
        frameVersionAssetRepository.deleteByFrameVersionIdIn(versionIds);
        frameVersionRepository.deleteByIdIn(versionIds);
    }
}
