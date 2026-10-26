package com.harucut.frame.repository;

import com.harucut.frame.entity.Frame;
import com.harucut.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FrameRepository extends JpaRepository<Frame, Long> {

    // 발행 번호를 매길 때 같은 프레임의 동시 발행을 줄 세운다 — max+1 이 겹치지 않게
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Frame f where f.id = :id")
    Optional<Frame> findByIdForUpdate(Long id);

    @Query("select f.id from Frame f where f.user.id = :userId")
    List<Long> findIdsByUserId(Long userId);

    // 목록은 프레임 N개 × 지연 컴포넌트 = N+1의 전형이라 fetch join으로 한 방에 가져온다.
    @Query("select f from Frame f left join fetch f.components where f.user = :user order by f.createdAt desc")
    List<Frame> findAllWithComponentsByUser(@Param("user") User user);

    @Query("select f from Frame f left join fetch f.components where f.isSystem = true order by f.createdAt desc")
    List<Frame> findAllWithComponentsBySystem();

    // 생성 시 한도 판정 — 소프트 캡 밖에 숨겨진 것까지 포함한 "보관 총량"
    long countByUser(User user);

    // 단건 조회 시 소프트 캡 판정 — 자기보다 최신인 프레임 수
    long countByUserAndCreatedAtAfter(User user, LocalDateTime createdAt);

    // FrameCountPort 어댑터용 — subscription의 사용량 API
    long countByUserId(Long userId);

    @Query("select f from Frame f left join fetch f.components where f.user.id = :userId")
    List<Frame> findAllWithComponentsByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("delete from FrameComponent c where c.frame.id in (select f.id from Frame f where f.user.id = :userId)")
    void deleteComponentsByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("delete from Frame f where f.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
