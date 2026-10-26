package com.harucut.project.repository;

import com.harucut.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByPublicId(String publicId);

    List<Project> findAllByWorkspaceIdOrderByIdDesc(Long workspaceId);

    List<Project> findAllByIdIn(Collection<Long> ids);
}
