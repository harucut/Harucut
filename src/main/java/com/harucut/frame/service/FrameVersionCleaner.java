package com.harucut.frame.service;

import com.harucut.frame.repository.FrameVersionAssetRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 프레임을 지울 때 그 발행본과 발행본의 자산 참조를 먼저 지운다(FK). 자산 자체는 지우지 않는다 —
 * 다른 프레임·초안이 참조할 수 있어 미참조 자산 정리 배치가 센다.
 * P2A 에서 행사가 쓰는 발행본은 지울 수 없게 막는다(EventFrame).
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
        frameVersionAssetRepository.deleteByFrameVersionIdIn(versionIds);
        frameVersionRepository.deleteByIdIn(versionIds);
    }
}
