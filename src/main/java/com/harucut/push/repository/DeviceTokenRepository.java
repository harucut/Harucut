package com.harucut.push.repository;

import com.harucut.push.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    // 새 토큰이면 넣고, 있으면 이 계정으로 옮기며 등록 시각을 갱신한다. 유니크 위반을 예외로 받지 않는다(받으면 롤백 전용이 된다)
    @Modifying(clearAutomatically = true)
    @Query(value = "insert into device_token (user_id, token, platform, last_registered_at, created_at, updated_at) "
            + "values (:userId, :token, :platform, :now, :now, :now) "
            + "on duplicate key update user_id = :userId, platform = :platform, last_registered_at = :now, updated_at = :now",
            nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("token") String token, @Param("platform") String platform,
               @Param("now") LocalDateTime now);

    // 최근 등록 순 — 상한 밖(뒤쪽)이 지울 대상이다
    @Query("select d.id from DeviceToken d where d.userId = :userId order by d.lastRegisteredAt desc, d.id desc")
    List<Long> findIdsByUserIdNewestFirst(@Param("userId") Long userId);

    @Query("select d.token from DeviceToken d where d.userId = :userId")
    List<String> findTokensByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.userId = :userId and d.token = :token")
    int deleteByUserIdAndToken(@Param("userId") Long userId, @Param("token") String token);

    // Expo 가 DeviceNotRegistered 라고 답한 토큰 — 어느 계정 것이든 지운다(그 기기는 더 이상 받지 않는다)
    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.token in :tokens")
    int deleteByTokens(@Param("tokens") Collection<String> tokens);

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceToken d where d.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
