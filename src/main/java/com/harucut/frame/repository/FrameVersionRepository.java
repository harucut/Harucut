package com.harucut.frame.repository;

import com.harucut.frame.entity.FrameVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FrameVersionRepository extends JpaRepository<FrameVersion, Long> {

    List<FrameVersion> findAllByFrameIdOrderByVersionNoDesc(Long frameId);

    Optional<FrameVersion> findByFrameIdAndVersionNo(Long frameId, int versionNo);

    // 일반 읽기(잠금 없음) — 번호는 프레임 카운터로 매기고, 이것은 카운터 이전에 매긴 번호의 하한으로만 쓴다
    @Query("select coalesce(max(v.versionNo), 0) from FrameVersion v where v.frameId = :frameId")
    int findMaxVersionNo(@Param("frameId") Long frameId);

    // 프레임마다 최신 발행본 하나
    @Query("""
            select v from FrameVersion v where v.frameId in :frameIds
            and v.versionNo = (select max(v2.versionNo) from FrameVersion v2 where v2.frameId = v.frameId)
            """)
    List<FrameVersion> findLatestByFrameIdIn(Collection<Long> frameIds);

    @Query("select v.id from FrameVersion v where v.frameId in :frameIds")
    List<Long> findIdsByFrameIdIn(Collection<Long> frameIds);

    // 행사 발행본(event_frame)이 쓰는 발행본 수. event 패키지에 의존하지 않으려고 네이티브로 센다
    @Query(value = "select count(*) from event_frame where frame_version_id in (:ids)", nativeQuery = true)
    long countUsedByEvents(Collection<Long> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FrameVersion v where v.id in :ids")
    void deleteByIdIn(Collection<Long> ids);
}
