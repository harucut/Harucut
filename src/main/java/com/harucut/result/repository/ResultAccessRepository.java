package com.harucut.result.repository;

import com.harucut.result.entity.ResultAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResultAccessRepository extends JpaRepository<ResultAccess, Long> {

    Optional<ResultAccess> findByTokenHash(String tokenHash);

    // 보관 종료 30일 뒤 — 그 행사 결과의 토큰 행
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ResultAccess r where r.composeJobId in (select j.id from ComposeJob j where j.eventId = :eventId)")
    int deleteByEventId(@Param("eventId") Long eventId);
}
