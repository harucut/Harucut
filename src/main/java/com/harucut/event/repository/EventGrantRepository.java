package com.harucut.event.repository;

import com.harucut.event.entity.EventGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventGrantRepository extends JpaRepository<EventGrant, Long> {

    Optional<EventGrant> findByEventIdAndUserId(Long eventId, Long userId);

    List<EventGrant> findAllByEventIdOrderByIdAsc(Long eventId);

    List<EventGrant> findAllByUserId(Long userId);

    // 멤버 회수 — 그 공간 행사들에 대한 그 사람의 권한
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EventGrant g where g.userId = :userId "
            + "and g.eventId in (select e.id from Event e where e.workspaceId = :workspaceId)")
    int deleteByUserIdInWorkspace(@Param("userId") Long userId, @Param("workspaceId") Long workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EventGrant g where g.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
