package com.harucut.media.repository;

import com.harucut.media.entity.MediaShare;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface MediaShareRepository extends JpaRepository<MediaShare, Long> {

    Optional<MediaShare> findByToken(String token);

    Optional<MediaShare> findByMediaId(Long mediaId);

    // 사진마다 하나 — 동시에 두 번 눌러도 유니크 위반(→ 롤백 전용 → 500) 없이 먼저 들어간 쪽이 남는다
    @Modifying
    @Query(value = "insert ignore into media_share (media_id, token, created_at, updated_at) "
            + "values (:mediaId, :token, :now, :now)", nativeQuery = true)
    int insertIfAbsent(Long mediaId, String token, LocalDateTime now);

    // 잠금 읽기 — 같은 트랜잭션의 앞선 읽기 스냅샷(MySQL REPEATABLE READ)에 없던, 방금 커밋된 다른 요청의 행도 본다
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from MediaShare s where s.mediaId = :mediaId")
    Optional<MediaShare> lockByMediaId(Long mediaId);

    // 사진을 지우기 전에 — FK 가 사진을 참조한다
    @Modifying
    @Query("delete from MediaShare s where s.mediaId = :mediaId")
    int deleteByMediaId(Long mediaId);

    // 탈퇴 — 그 사람의 사진 행을 벌크로 지우기 전에
    @Modifying(clearAutomatically = true)
    @Query("delete from MediaShare s where s.mediaId in (select m.id from UserMedia m where m.user.id = :userId)")
    int deleteByOwnerId(Long userId);
}
