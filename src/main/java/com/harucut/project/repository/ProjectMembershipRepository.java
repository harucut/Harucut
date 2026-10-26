package com.harucut.project.repository;

import com.harucut.project.entity.ProjectMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, Long> {

    Optional<ProjectMembership> findByProjectIdAndUserId(Long projectId, Long userId);

    List<ProjectMembership> findAllByProjectIdOrderByIdAsc(Long projectId);

    List<ProjectMembership> findAllByUserId(Long userId);

    // 공간에서 내보낸 사람의 그 공간 프로젝트 권한 — 멤버 회수와 함께 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ProjectMembership m where m.userId = :userId and m.projectId in "
            + "(select p.id from Project p where p.workspaceId = :workspaceId)")
    int deleteByUserIdInWorkspace(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId);
}
