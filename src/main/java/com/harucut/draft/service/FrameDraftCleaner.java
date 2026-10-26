package com.harucut.draft.service;

import com.harucut.draft.repository.FrameDraftAssetRepository;
import com.harucut.draft.repository.FrameDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 초안은 프레임·공간을 FK 로 참조한다. 프레임 삭제·회원 탈퇴는 초안과 그 자산 참조를 먼저 지운다.
 * 자산 자체는 지우지 않는다(미참조 자산 정리 배치 몫, 탈퇴는 WorkspaceService 가 공간 자산을 지운다).
 */
@Component
@RequiredArgsConstructor
public class FrameDraftCleaner {

    private final FrameDraftRepository frameDraftRepository;
    private final FrameDraftAssetRepository frameDraftAssetRepository;

    public void deleteForFrames(Collection<Long> frameIds) {
        if (!frameIds.isEmpty()) {
            delete(frameDraftRepository.findIdsByFrameIdIn(frameIds));
        }
    }

    public void deleteForUser(Long userId) {
        delete(frameDraftRepository.findIdsByUserId(userId));
    }

    void delete(List<Long> draftIds) {
        if (draftIds.isEmpty()) {
            return;
        }
        frameDraftAssetRepository.deleteByFrameDraftIdIn(draftIds);
        frameDraftRepository.deleteByIdIn(draftIds);
    }
}
