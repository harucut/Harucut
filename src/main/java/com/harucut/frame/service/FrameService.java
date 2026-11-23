package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.draft.service.FrameDraftCleaner;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.policy.FrameSubscriptionPolicy;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
@Transactional
@RequiredArgsConstructor
public class FrameService {

    private static final Set<WorkspaceRole> FRAME_EDIT_ROLES =
            Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.DESIGNER);
    private static final Set<WorkspaceRole> FRAME_READ_ROLES = EnumSet.allOf(WorkspaceRole.class);

    private final FrameRepository frameRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final FrameComponentAssembler frameComponentAssembler;
    private final FrameSubscriptionPolicy frameSubscriptionPolicy;
    private final WorkspaceService workspaceService;
    private final FrameVersionCleaner frameVersionCleaner;
    private final FrameDraftCleaner frameDraftCleaner;
    private final PlatformTransactionManager transactionManager;

    /**
     * 개수 상한을 동시 생성에도 지키려고, 생성 트랜잭션의 첫 조회로 상한의 주인(지정한 공간·사용자) 행을 잠근다.
     * 잠금 읽기는 스냅샷을 만들지 않으므로 그 뒤 일반 읽기(세기)는 잠금을 얻은 뒤의 커밋까지 본다(REPEATABLE READ).
     * 프레임 행 범위를 잠금 읽기로 세면 다른 사용자·공간과 같은 빈 구간 gap lock 으로 교착될 수 있어 그렇게 하지 않는다.
     * <p>개인 공간 지연 생성(REQUIRES_NEW)은 잠금 전에 따로 끝낸다 — 사용자 행을 잡은 채 만들면 그 insert 의 FK 검사가
     * 같은 사용자 행을 기다리고, 바깥은 생성이 끝나기를 기다려 잠금 시간 초과까지 멈춘다. 그래서 이 메서드는 트랜잭션을 열지 않는다.
     * <p>잠금 순서는 늘 「공간 → 사용자」: workspaceId 를 뺀 요청도 미리 개인 공간 id 로 바꿔 같은 순서로 잠근다. 사용자만 잠그면
     * frame insert 의 FK 검사(fk_frame_workspace)가 공간 행을 기다리고, 그 공간을 먼저 잠근 같은 사용자의 다른 요청과 교착된다
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public FrameResponse createFrame(String publicId, FrameCreateRequest request) {
        String workspacePublicId = request.workspaceId() != null
                ? request.workspaceId()
                : workspaceService.personalWorkspaceOf(getUser(publicId).getId()).getPublicId();
        return new TransactionTemplate(transactionManager)
                .execute(status -> createFrameLocked(publicId, workspacePublicId, request));
    }

    private FrameResponse createFrameLocked(String publicId, String workspacePublicId, FrameCreateRequest request) {
        workspaceRepository.lockByPublicId(workspacePublicId);
        User user = userRepository.lockByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), FRAME_EDIT_ROLES);
        if (workspace.isPersonal()) {
            frameSubscriptionPolicy.assertFrameRetentionLimit(user.getId(), (int) frameRepository.countPersonalByUser(user));
        } else {
            // 조직 공간 프레임은 만든 사람의 개인 요금제 한도를 받지 않는다 — 공간의 것이다(조직 요금제는 P4)
            frameComponentAssembler.assertWorkspaceRoom(frameRepository.countByWorkspaceId(workspace.getId()));
        }

        Frame frame = frameComponentAssembler.assembleOwned(user, request, workspace.getId());
        frame.assignWorkspace(workspace.getId());
        return frameComponentAssembler.toFrameResponse(frameRepository.save(frame));
    }

    /** 조직 공간의 프레임 목록 — 멤버 누구나. 만든 사람과 무관하게 공간의 프레임 전부(최신순) */
    @Transactional(readOnly = true)
    public List<FrameResponse> getWorkspaceFrames(String publicId, String workspacePublicId) {
        User user = getUser(publicId);
        Workspace workspace = workspaceService.requireRole(workspacePublicId, user.getId(), FRAME_READ_ROLES);
        return frameRepository.findAllWithComponentsByWorkspaceId(workspace.getId()).stream()
                .map(frameComponentAssembler::toFrameResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FrameResponse> getMyFrames(String publicId) {
        User user = getUser(publicId);
        LocalDateTime cutoff = frameSubscriptionPolicy.resolveHistoryCutoff(user.getId());
        Integer cap = frameSubscriptionPolicy.resolveFrameRetentionCap(user.getId());

        // 소프트 캡: 지우지 않고 자른다 — cutoff 필터를 먼저, 최신 cap개 제한을 나중에
        Stream<Frame> myFrames = frameRepository.findPersonalWithComponentsByUser(user).stream()
                .filter(frame -> withinHistoryWindow(frame.getCreatedAt(), cutoff));
        if (cap != null) {
            myFrames = myFrames.limit(cap);
        }

        // 최종 순서 계약: [내 개인 프레임(최신순)] + [시스템 프레임(최신순)]. 조직 공간 프레임은 공간 목록에서 본다
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
        // 수정에도 조회와 같은 관문 — 안 보이는 프레임은 덮어쓸 수도 없다(조직 공간이면 편집 역할)
        assertEditable(frame, user);

        // 교체와 잃은 참조 수집은 관리자와 같은 규칙이라 어셈블러가 소유한다
        FrameVersions.assertExpected(frame, request.version());
        frameComponentAssembler.replaceContent(frame, request, user);

        // 새 컴포넌트의 id는 INSERT가 나가야 생긴다 — 응답 계약(components[].id)에 필요해 지금 flush
        FrameVersions.flush(frameRepository, frame);
        return frameComponentAssembler.toFrameResponse(frame);
    }

    public void deleteFrame(String publicId, Long frameId) {
        User user = getUser(publicId);
        Frame frame = getFrameById(frameId);
        // 삭제는 소유권(조직 공간이면 편집 역할)만 — 숨겨진 프레임도 정리는 가능해야 한다
        Optional<Workspace> organization = workspaceService.organization(frame.getWorkspaceId());
        if (organization.isPresent()) {
            workspaceService.requireRole(organization.get().getId(), user.getId(), FRAME_EDIT_ROLES);
        } else {
            validateOwner(frame, user);
        }

        frameComponentAssembler.scheduleDeletion(frameComponentAssembler.collectAllKeys(frame));
        frameVersionCleaner.deleteForFrames(List.of(frame.getId()));   // 발행본·초안이 프레임을 FK 로 참조한다
        frameDraftCleaner.deleteForFrames(List.of(frame.getId()));
        frameRepository.delete(frame);   // 컴포넌트는 cascade + orphanRemoval
    }

    /** 발행본 목록·단건처럼 "읽을 수 있는 사용자 프레임"의 관문 — 단건 조회와 같다(시스템·남의 프레임은 404) */
    @Transactional(readOnly = true)
    public Frame getReadableFrame(User user, Long frameId) {
        Frame frame = getFrameById(frameId);
        assertReadable(frame, user);
        return frame;
    }

    /** 발행·초안처럼 프레임을 바꾸는 기능의 관문 — 수정과 같다(조직 공간이면 OWNER·ADMIN·DESIGNER) */
    @Transactional(readOnly = true)
    public Frame getEditableFrame(User user, Long frameId) {
        Frame frame = getFrameById(frameId);
        assertEditable(frame, user);
        return frame;
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

    // 단건 조회 관문. 조직 공간 프레임이면 멤버면 된다(개인 요금제 판정 없음).
    // 개인 프레임이면 소유권 → 보관 기간 → 소프트 캡 순서
    private void assertReadable(Frame frame, User user) {
        assertAccess(frame, user, FRAME_READ_ROLES);
    }

    private void assertEditable(Frame frame, User user) {
        assertAccess(frame, user, FRAME_EDIT_ROLES);
    }

    private void assertAccess(Frame frame, User user, Set<WorkspaceRole> organizationRoles) {
        Optional<Workspace> organization = workspaceService.organization(frame.getWorkspaceId());
        if (organization.isPresent()) {
            // 멤버가 아니거나 역할이 모자라면 404 — 남의 프레임과 같은 응답(존재 여부를 숨긴다)
            workspaceService.requireRole(organization.get().getId(), user.getId(), organizationRoles);
            return;
        }
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
        if (frameRepository.countPersonalByUserCreatedAfter(user, frame.getCreatedAt()) >= cap) {
            throw new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED);
        }
    }

    private boolean withinHistoryWindow(LocalDateTime createdAt, LocalDateTime cutoff) {
        return cutoff == null || createdAt == null || !createdAt.isBefore(cutoff);
    }
}
