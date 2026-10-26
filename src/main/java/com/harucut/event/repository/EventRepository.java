package com.harucut.event.repository;

import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByPublicId(String publicId);

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Event> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    // 공간의 운영 중 행사 — 잠금 읽기(FOR UPDATE)라 REPEATABLE READ 스냅샷이 아니라 최신 커밋을 본다.
    // 공간 행 잠금을 기다린 뒤 일반 읽기로 세면, 잠금 전에 잡힌 스냅샷 때문에 방금 커밋된 LIVE 를 놓친다
    @Query(value = "select event_id from event where workspace_id = :workspaceId and status = 'LIVE' for update", nativeQuery = true)
    List<Long> lockLiveEventIds(@Param("workspaceId") Long workspaceId);
}
