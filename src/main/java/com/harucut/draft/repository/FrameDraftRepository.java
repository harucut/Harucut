package com.harucut.draft.repository;

import com.harucut.draft.entity.FrameDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FrameDraftRepository extends JpaRepository<FrameDraft, Long> {

    Optional<FrameDraft> findByPublicIdAndUserId(String publicId, Long userId);

    List<FrameDraft> findAllByUserIdOrderByUpdatedAtDesc(Long userId);

    List<FrameDraft> findAllByUserIdAndFrameIdOrderByUpdatedAtDesc(Long userId, Long frameId);

    List<FrameDraft> findAllByUserIdAndFrameIdIsNullOrderByUpdatedAtDesc(Long userId);

    /**
     * revision 이 기대값일 때만 저장하고 1 올린다. 영향 행 0 = 다른 기기가 먼저 저장했다.
     * 벌크 UPDATE 라 auditing 이 안 돈다 — updated_at 을 직접 넣는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FrameDraft d
            set d.document = :document, d.revision = d.revision + 1, d.updatedAt = :now
            where d.id = :id and d.revision = :expectedRevision
            """)
    int updateIfRevision(Long id, int expectedRevision, String document, LocalDateTime now);

    @Query("select d.id from FrameDraft d where d.frameId in :frameIds")
    List<Long> findIdsByFrameIdIn(Collection<Long> frameIds);

    @Query("select d.id from FrameDraft d where d.userId = :userId")
    List<Long> findIdsByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FrameDraft d where d.id in :ids")
    void deleteByIdIn(Collection<Long> ids);
}
