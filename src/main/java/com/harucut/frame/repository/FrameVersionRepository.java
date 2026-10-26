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

    // 프레임 행을 잠근 뒤 부른다. 잠금 읽기(FOR UPDATE)라 잠금을 기다리는 동안 커밋된 발행본까지 본다
    // (일반 읽기면 잠금 전에 잡힌 스냅샷 때문에 같은 번호를 매겨 uk_frame_version_frame_no 위반이 난다)
    @Query(value = "select version_no from frame_version where frame_id = :frameId order by version_no desc limit 1 for update",
            nativeQuery = true)
    List<Integer> lockLatestVersionNo(@Param("frameId") Long frameId);

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
