package com.harucut.workspace.repository;

import com.harucut.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    Optional<Workspace> findByPersonalOwnerUserId(Long userId);

    Optional<Workspace> findByPublicId(String publicId);

    List<Workspace> findAllByIdIn(Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Workspace w WHERE w.personalOwnerUserId = :userId")
    void deletePersonalByOwner(Long userId);
}
