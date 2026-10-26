package com.harucut.event.repository;

import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByPublicId(String publicId);

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Event> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    long countByWorkspaceIdAndStatus(Long workspaceId, EventStatus status);
}
