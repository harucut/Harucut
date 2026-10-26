package com.harucut.event.repository;

import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByPublicId(String publicId);

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Event> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    long countByWorkspaceIdAndStatus(Long workspaceId, EventStatus status);

    List<Event> findAllByStatusIn(Collection<EventStatus> statuses);

    // 관리자 검색 — slug·이름 부분 일치(소문자)
    @Query("select e from Event e where lower(e.slug) like :pattern escape '!' or lower(e.name) like :pattern escape '!' order by e.id desc")
    List<Event> search(@Param("pattern") String pattern, Pageable pageable);

    List<Event> findAllByStatusAndArchivedAtBefore(EventStatus status, LocalDateTime before);
}
