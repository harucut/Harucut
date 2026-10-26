package com.harucut.workspace.service;

import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final PersonalWorkspaceCreator personalWorkspaceCreator;
    private final AssetRepository assetRepository;
    private final S3Deleter s3Deleter;

    /**
     * 사용자의 개인 공간. 없으면 만든다 — V2 migration 이 기존 사용자를 백필하지만, flyway 가 꺼진 운영에서는
     * Hibernate update 가 테이블만 만들고 백필은 돌지 않으므로 지연 생성이 기본 경로다.
     */
    public Workspace personalWorkspaceOf(Long userId) {
        return workspaceRepository.findByPersonalOwnerUserId(userId)
                .orElseGet(() -> createPersonal(userId));
    }

    private Workspace createPersonal(Long userId) {
        try {
            return personalWorkspaceCreator.create(userId);
        } catch (DataIntegrityViolationException e) {
            return personalWorkspaceCreator.find(userId);   // 동시 요청이 먼저 만들었다
        }
    }

    public List<WorkspaceResponse> listMine(String publicId) {
        User user = getUser(publicId);
        personalWorkspaceOf(user.getId());

        List<WorkspaceMembership> memberships =
                membershipRepository.findAllByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE);
        Map<Long, Workspace> workspaces = workspaceRepository
                .findAllByIdIn(memberships.stream().map(WorkspaceMembership::getWorkspaceId).toList())
                .stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));

        return memberships.stream()
                .filter(m -> workspaces.containsKey(m.getWorkspaceId()))
                .map(m -> WorkspaceResponse.of(workspaces.get(m.getWorkspaceId()), m.getRole()))
                .toList();
    }

    /**
     * 공간 public id 를 내부 공간으로 바꾸면서 권한을 확인한다. 멤버가 아니거나 역할이 모자라면
     * 공간이 있다는 사실도 알리지 않는다(404).
     */
    @Transactional(readOnly = true)
    public Workspace requireRole(String workspacePublicId, Long userId, Collection<WorkspaceRole> allowed) {
        Workspace workspace = workspaceRepository.findByPublicId(workspacePublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        membershipRepository.findByWorkspaceIdAndUserIdAndStatus(workspace.getId(), userId, MembershipStatus.ACTIVE)
                .filter(m -> allowed.contains(m.getRole()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        return workspace;
    }

    /** 이 공간의 활성 멤버인가. 자산·초안처럼 공간 id 를 이미 가진 행의 접근 판정에 쓴다 */
    @Transactional(readOnly = true)
    public boolean isMember(Long workspaceId, Long userId) {
        return workspaceId != null && membershipRepository
                .findByWorkspaceIdAndUserIdAndStatus(workspaceId, userId, MembershipStatus.ACTIVE)
                .isPresent();
    }

    /**
     * 회원 탈퇴 마지막 단계(UserExitService). 다른 탈퇴 핸들러가 이 공간을 참조하는 데이터(프레임 등)를
     * 먼저 지운 뒤에 불려야 한다 — FK 때문에 순서가 의미를 가진다.
     */
    public void deleteForUser(Long userId) {
        // 개인 공간의 자산이 공간을 FK 로 참조한다 — 자산 행을 먼저 지우고 S3 객체는 커밋 후 지운다.
        // 자산을 참조하던 프레임·발행본은 앞선 탈퇴 핸들러가 이미 지웠다
        workspaceRepository.findByPersonalOwnerUserId(userId).ifPresent(personal -> {
            List<String> keys = assetRepository.findKeysByWorkspaceId(personal.getId());
            assetRepository.deleteByWorkspaceId(personal.getId());
            s3Deleter.deleteAfterCommit(keys);
        });
        membershipRepository.deleteByUserId(userId);
        workspaceRepository.deletePersonalByOwner(userId);
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }
}
