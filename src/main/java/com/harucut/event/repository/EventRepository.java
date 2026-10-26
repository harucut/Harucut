package com.harucut.event.repository;

import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

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

    List<Event> findAllByStatusIn(Collection<EventStatus> statuses);

    // 관리자 검색 — slug·이름 부분 일치(소문자)
    @Query("select e from Event e where lower(e.slug) like :pattern escape '!' or lower(e.name) like :pattern escape '!' order by e.id desc")
    List<Event> search(@Param("pattern") String pattern, Pageable pageable);

    List<Event> findAllByStatusAndArchivedAtBefore(EventStatus status, LocalDateTime before);

    // 공간의 운영 중 행사 — 잠금 읽기(FOR UPDATE)라 REPEATABLE READ 스냅샷이 아니라 최신 커밋을 본다.
    // 공간 행 잠금을 기다린 뒤 일반 읽기로 세면, 잠금 전에 잡힌 스냅샷 때문에 방금 커밋된 LIVE 를 놓친다
    @Query(value = "select event_id from event where workspace_id = :workspaceId and status = 'LIVE' for update", nativeQuery = true)
    List<Long> lockLiveEventIds(@Param("workspaceId") Long workspaceId);

    // 지금 보관 일수 — 잠금 읽기라 최신 커밋을 보고, 이 트랜잭션이 끝날 때까지 설정 저장(행사 행 UPDATE)이 기다린다
    @Query(value = "select result_retention_days from event where event_id = :eventId for update", nativeQuery = true)
    List<Integer> lockRetentionDays(@Param("eventId") Long eventId);
}
