package com.harucut.workspace.repository;

import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, Long> {

    List<WorkspaceMembership> findAllByUserIdAndStatus(Long userId, MembershipStatus status);

    Optional<WorkspaceMembership> findByWorkspaceIdAndUserIdAndStatus(Long workspaceId, Long userId,
                                                                      MembershipStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WorkspaceMembership m WHERE m.userId = :userId")
    void deleteByUserId(Long userId);
}
