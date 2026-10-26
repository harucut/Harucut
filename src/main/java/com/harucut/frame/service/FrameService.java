package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.policy.FrameSubscriptionPolicy;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Service
@Transactional
@RequiredArgsConstructor
public class FrameService {

    private final FrameRepository frameRepository;
    private final UserRepository userRepository;
    private final FrameAssetManager frameAssetManager;
    private final FrameComponentAssembler frameComponentAssembler;
    private final FrameSubscriptionPolicy frameSubscriptionPolicy;
    private final WorkspaceService workspaceService;

    public FrameResponse createFrame(String publicId, FrameCreateRequest request) {
        User user = getUser(publicId);
        frameSubscriptionPolicy.assertFrameRetentionLimit(user.getId(), (int) frameRepository.countByUser(user));

        Frame frame = frameComponentAssembler.assembleOwned(user, request);
        frame.assignWorkspace(workspaceService.personalWorkspaceOf(user.getId()).getId());
        return frameComponentAssembler.toFrameResponse(frameRepository.save(frame));
    }

    @Transactional(readOnly = true)
    public List<FrameResponse> getMyFrames(String publicId) {
        User user = getUser(publicId);
        LocalDateTime cutoff = frameSubscriptionPolicy.resolveHistoryCutoff(user.getId());
        Integer cap = frameSubscriptionPolicy.resolveFrameRetentionCap(user.getId());

        // 소프트 캡: 지우지 않고 자른다 — cutoff 필터를 먼저, 최신 cap개 제한을 나중에
        Stream<Frame> myFrames = frameRepository.findAllWithComponentsByUser(user).stream()
                .filter(frame -> withinHistoryWindow(frame.getCreatedAt(), cutoff));
        if (cap != null) {
            myFrames = myFrames.limit(cap);
        }

        // 최종 순서 계약: [내 프레임(최신순)] + [시스템 프레임(최신순)]
        return Stream.concat(myFrames, frameRepository.findAllWithComponentsBySystem().stream())
                .map(frameComponentAssembler::toFrameResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FrameResponse getFrame(String publicId, Long frameId) {
        return frameComponentAssembler.toFrameResponse(
                getComposableFrame(getUser(publicId), frameId));
    }

    // 네컷 합성이 쓸 프레임 — 단건 조회와 정확히 같은 관문을 통과한 엔티티를 내준다.
    // 규칙이 두 벌이면 "목록엔 안 보이는데 합성은 되는" 구멍이 생긴다
    @Transactional(readOnly = true)
    public Frame getComposableFrame(User user, Long frameId) {
        Frame frame = getFrameById(frameId);
        // 시스템 프레임은 소유자·보관 기간·캡 전부 우회 — 누구나 읽는다
        if (!frame.isSystem()) {
            assertReadable(frame, user);
        }
        return frame;
    }

    public FrameResponse updateFrame(String publicId, Long frameId, FrameCreateRequest request) {
        User user = getUser(publicId);
        Frame frame = getFrameById(frameId);
        // 수정에도 조회와 같은 관문 — 안 보이는 프레임은 덮어쓸 수도 없다
        assertReadable(frame, user);

        // 교체와 잃은 참조 수집은 관리자와 같은 규칙이라 어셈블러가 소유한다
        frameComponentAssembler.replaceContent(frame, request);

        // 새 컴포넌트의 id는 INSERT가 나가야 생긴다 — 응답 계약(components[].id)에 필요해 지금 flush
        frameRepository.saveAndFlush(frame);
        return frameComponentAssembler.toFrameResponse(frame);
    }

    public void deleteFrame(String publicId, Long frameId) {
        User user = getUser(publicId);
        Frame frame = getFrameById(frameId);
        validateOwner(frame, user);   // 삭제는 소유권만 — 숨겨진 프레임도 정리는 가능해야 한다

        frameAssetManager.deleteAfterCommit(frameComponentAssembler.collectAllKeys(frame));
        frameRepository.delete(frame);   // 컴포넌트는 cascade + orphanRemoval
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private Frame getFrameById(Long frameId) {
        return frameRepository.findById(frameId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private void validateOwner(Frame frame, User user) {
        // 시스템 프레임(user=null)도 여기서 걸린다 — 사용자 API로는 수정·삭제 불가.
        // 남의 프레임은 403이 아니라 404 — id가 순차 Long이라 403을 주면 존재 여부가 열거된다.
        // 진짜 없는 프레임(getFrameById)과 같은 코드·기본 메시지여야 응답으로 구분이 안 된다
        if (frame.getUser() == null || !frame.getUser().getId().equals(user.getId())) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
    }

    // 단건 조회와 수정이 공유하는 관문: 소유권 → 보관 기간 → 소프트 캡 순서
    private void assertReadable(Frame frame, User user) {
        validateOwner(frame, user);
        frameSubscriptionPolicy.assertHistoryAccessible(user.getId(), frame.getCreatedAt());
        assertWithinRetentionCap(frame, user);
    }

    // 소프트 캡 판정: 자기보다 최신인 프레임이 cap개 이상이면 이 프레임은 목록에서 잘린 상태다
    private void assertWithinRetentionCap(Frame frame, User user) {
        Integer cap = frameSubscriptionPolicy.resolveFrameRetentionCap(user.getId());
        if (cap == null) {
            return;
        }
        if (frameRepository.countByUserAndCreatedAtAfter(user, frame.getCreatedAt()) >= cap) {
            throw new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED);
        }
    }

    private boolean withinHistoryWindow(LocalDateTime createdAt, LocalDateTime cutoff) {
        return cutoff == null || createdAt == null || !createdAt.isBefore(cutoff);
    }
}
