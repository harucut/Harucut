package com.harucut.workspace.repository;

import com.harucut.workspace.entity.WorkspaceInvitation;
import com.harucut.workspace.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, Long> {

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByPublicId(String publicId);

    List<WorkspaceInvitation> findAllByWorkspaceIdAndStatusOrderByIdDesc(Long workspaceId, InvitationStatus status);

    // 1회 소비 — 같은 토큰으로 동시에 두 번 수락해도 한쪽만 1을 받는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkspaceInvitation i set i.status = com.harucut.workspace.enums.InvitationStatus.ACCEPTED "
            + "where i.id = :id and i.status = com.harucut.workspace.enums.InvitationStatus.OPEN")
    int consume(@Param("id") Long id);
}
