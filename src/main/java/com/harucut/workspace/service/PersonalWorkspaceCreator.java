package com.harucut.workspace.service;

import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인 공간 지연 생성. 호출자 트랜잭션과 분리된 새 트랜잭션에서 만든다(REQUIRES_NEW).
 *
 * <p>같은 사용자의 첫 요청 둘이 동시에 오면 둘 다 "없다"를 보고 만들려 한다. 하나는
 * {@code uk_workspace_personal_owner} 에 걸려 실패한다. 호출자 트랜잭션 안에서 INSERT 하면 그 실패가
 * 호출자 트랜잭션까지 롤백 전용으로 만들고, MySQL REPEATABLE READ 에서는 같은 트랜잭션의 재조회가
 * 상대가 커밋한 행을 못 본다. 그래서 생성과 재조회를 모두 새 트랜잭션에서 한다.
 */
@Component
@RequiredArgsConstructor
public class PersonalWorkspaceCreator {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Workspace create(Long userId) {
        Workspace workspace = workspaceRepository.saveAndFlush(Workspace.personal(userId));
        membershipRepository.save(WorkspaceMembership.owner(workspace.getId(), userId));
        return workspace;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Workspace find(Long userId) {
        return workspaceRepository.findByPersonalOwnerUserId(userId).orElseThrow(() ->
                new IllegalStateException("개인 공간 생성 경합 뒤에도 공간이 없다: userId=" + userId));
    }
}
