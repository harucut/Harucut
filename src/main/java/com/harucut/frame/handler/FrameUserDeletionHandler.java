package com.harucut.frame.handler;

import com.harucut.auth.service.UserDeletionHandler;
import com.harucut.draft.service.FrameDraftCleaner;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameComponentAssembler;
import com.harucut.frame.service.FrameVersionCleaner;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FrameUserDeletionHandler implements UserDeletionHandler {

    private final FrameRepository frameRepository;
    private final WorkspaceRepository workspaceRepository;
    private final FrameComponentAssembler frameComponentAssembler;
    private final FrameVersionCleaner frameVersionCleaner;
    private final FrameDraftCleaner frameDraftCleaner;

    // JPQL 벌크 DELETE는 cascade/orphanRemoval을 우회하므로 컴포넌트를 직접, 먼저 지운다 (FK)
    @Transactional
    @Override
    public void handleUserDeletion(Long userId) {
        // 개인 공간의 프레임만 지운다. 조직 공간에 만든 프레임은 행사·다른 멤버가 쓰므로 남긴다(R5 14절 —
        // 멤버 탈퇴로 다른 고객의 행사 자료를 연쇄 삭제하지 않는다)
        Long personalWorkspaceId = workspaceRepository.findByPersonalOwnerUserId(userId)
                .map(Workspace::getId).orElse(null);
        List<Frame> frames = frameRepository.findPersonalWithComponents(userId, personalWorkspaceId);
        List<Long> frameIds = frames.stream().map(Frame::getId).toList();
        List<String> keys = frames.stream()
                .flatMap(frame -> frameComponentAssembler.collectAllKeys(frame).stream())
                .toList();

        // 자산이 된 key 는 건너뛴다 — 개인 공간 자산은 탈퇴 마지막 단계(WorkspaceService)가 한꺼번에 지운다
        frameComponentAssembler.scheduleDeletion(keys);
        frameVersionCleaner.deleteForFrames(frameIds);
        frameDraftCleaner.deleteForUser(userId);   // 새 프레임 초안(frame_id NULL)까지 — 프레임보다 먼저(FK)
        if (!frameIds.isEmpty()) {
            frameRepository.deleteComponentsByFrameIdIn(frameIds);
            frameRepository.deleteByIdIn(frameIds);
        }
    }
}
