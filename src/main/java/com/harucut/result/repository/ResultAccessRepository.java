package com.harucut.result.repository;

import com.harucut.result.entity.ResultAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ResultAccessRepository extends JpaRepository<ResultAccess, Long> {

    Optional<ResultAccess> findByTokenHash(String tokenHash);

    // 그 작업의 살아 있는 링크를 전부 회수한다. 이미 회수한 행의 시각은 덮지 않는다. 벌크 UPDATE 라 updated_at 을 직접 넣는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ResultAccess r set r.revokedAt = :now, r.updatedAt = :now where r.composeJobId = :composeJobId and r.revokedAt is null")
    int revokeByComposeJobId(@Param("composeJobId") Long composeJobId, @Param("now") LocalDateTime now);

    // 보관 종료 30일 뒤 — 그 행사 결과의 토큰 행
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ResultAccess r where r.composeJobId in (select j.id from ComposeJob j where j.eventId = :eventId)")
    int deleteByEventId(@Param("eventId") Long eventId);
}
