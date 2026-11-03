package com.harucut.workspace.repository;

import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, Long> {

    List<WorkspaceMembership> findAllByUserIdAndStatus(Long userId, MembershipStatus status);

    Optional<WorkspaceMembership> findByWorkspaceIdAndUserIdAndStatus(Long workspaceId, Long userId,
                                                                      MembershipStatus status);

    Optional<WorkspaceMembership> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMembership> findAllByWorkspaceIdAndStatusOrderByIdAsc(Long workspaceId, MembershipStatus status);

    // 활성 OWNER 행 — 잠금 읽기(FOR UPDATE). 두 OWNER 가 동시에 서로를 내려도 같은 행을 잠그므로 뒤쪽은 앞쪽 커밋을 보고 센다
    // (일반 읽기면 REPEATABLE READ 스냅샷이라 둘 다 2 를 읽고 OWNER 가 0 명이 된다)
    @Query(value = "select workspace_membership_id from workspace_membership "
            + "where workspace_id = :workspaceId and role = 'OWNER' and status = 'ACTIVE' for update", nativeQuery = true)
    List<Long> lockActiveOwnerIds(@Param("workspaceId") Long workspaceId);

    // 참여 중지 판정 — 활성 OWNER 중 계정이 ACTIVE 인 사람 수(WorkspaceService.participationSuspended). 요청마다 세므로 해제가 바로 보인다
    @Query("select count(m) from WorkspaceMembership m, User u where u.id = m.userId and m.workspaceId = :workspaceId "
            + "and m.role = com.harucut.workspace.enums.WorkspaceRole.OWNER "
            + "and m.status = com.harucut.workspace.enums.MembershipStatus.ACTIVE "
            + "and u.userStatus = com.harucut.user.enums.UserStatus.ACTIVE")
    long countUsableOwners(@Param("workspaceId") Long workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WorkspaceMembership m WHERE m.userId = :userId")
    void deleteByUserId(Long userId);
}
