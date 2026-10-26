package com.harucut.workspace.repository;

import com.harucut.workspace.entity.Workspace;
import jakarta.persistence.LockModeType;
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
}
