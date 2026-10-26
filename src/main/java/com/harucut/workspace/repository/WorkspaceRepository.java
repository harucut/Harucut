package com.harucut.workspace.repository;

import com.harucut.workspace.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    // 공간 단위 직렬화(동시 게시의 운영 중 행사 수 검사) — SELECT … FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> lockById(@Param("id") Long id);

    Optional<Workspace> findByPersonalOwnerUserId(Long userId);

    Optional<Workspace> findByPublicId(String publicId);

    List<Workspace> findAllByIdIn(Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Workspace w WHERE w.personalOwnerUserId = :userId")
    void deletePersonalByOwner(Long userId);

    // 관리자 검색 — 조직 공간 이름 또는 OWNER 이메일 부분 일치(소문자). 개인 공간은 계약 대상이 아니라 뺀다
    @Query("""
            select w from Workspace w where w.type = com.harucut.workspace.enums.WorkspaceType.ORGANIZATION
              and (lower(w.name) like :pattern escape '!' or w.id in (
                select m.workspaceId from WorkspaceMembership m, User u where u.id = m.userId
                  and m.role = com.harucut.workspace.enums.WorkspaceRole.OWNER
                  and m.status = com.harucut.workspace.enums.MembershipStatus.ACTIVE and lower(u.email) like :pattern escape '!'))
            order by w.id desc
            """)
    List<Workspace> searchOrganizations(@Param("pattern") String pattern, Pageable pageable);
}
