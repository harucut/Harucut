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

    // 발행 번호 카운터 — 프레임 행만 잠근다(행 잠금). 발행본 번호 범위를 잠금 읽기로 보면, 서로 다른 프레임의 첫 발행이
    // uk_frame_version_frame_no 의 같은 빈 구간에 gap lock 을 함께 잡고 서로의 insert 를 기다려 교착된다
    @Modifying(flushAutomatically = true)
    @Query(value = "update frame set last_version_no = last_version_no + 1 where frame_id = :id", nativeQuery = true)
    int bumpVersionNo(@Param("id") Long id);

    // 방금 올린 카운터 — 잠금 읽기라 최신 값이다(행은 위 UPDATE 로 이미 잠겨 있다)
    @Query(value = "select last_version_no from frame where frame_id = :id for update", nativeQuery = true)
    int lockedVersionNo(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query(value = "update frame set last_version_no = :versionNo where frame_id = :id", nativeQuery = true)
    int raiseVersionNo(@Param("id") Long id, @Param("versionNo") int versionNo);

    @Query("select f.id from Frame f where f.user.id = :userId")
    List<Long> findIdsByUserId(Long userId);

    // "개인 프레임" = 공간이 없던 옛 행이거나 조직 공간이 아닌 공간의 프레임. 조직 공간 프레임은 만든 사람의
    // 개인 목록·요금제 한도·보관 판정에서 빠진다 — 그 공간의 것이다(공간 목록·역할로 다룬다).
    // 개인 공간을 지연 생성하지 않으려고(읽기 전용 트랜잭션) 개인 공간 id 대신 조직 공간을 제외하는 식으로 쓴다
    String PERSONAL = """
            (f.workspaceId is null or f.workspaceId not in
             (select w.id from Workspace w where w.type = com.harucut.workspace.enums.WorkspaceType.ORGANIZATION))""";

    // 목록은 프레임 N개 × 지연 컴포넌트 = N+1의 전형이라 fetch join으로 한 방에 가져온다.
    @Query("select f from Frame f left join fetch f.components where f.user = :user and " + PERSONAL
            + " order by f.createdAt desc")
    List<Frame> findPersonalWithComponentsByUser(@Param("user") User user);

    @Query("select distinct f from Frame f left join fetch f.components where f.workspaceId = :workspaceId order by f.createdAt desc")
    List<Frame> findAllWithComponentsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    long countByWorkspaceId(Long workspaceId);

    // 행사에 연결할 수 있는 프레임 — 시스템 프레임과 그 공간의 프레임(행사 게시 검사와 같은 규칙)
    @Query("select f from Frame f where f.isSystem = true or f.workspaceId = :workspaceId order by f.isSystem asc, f.createdAt desc")
    List<Frame> findSystemOrWorkspace(@Param("workspaceId") Long workspaceId);

    @Query("select f from Frame f left join fetch f.components where f.isSystem = true order by f.createdAt desc")
    List<Frame> findAllWithComponentsBySystem();

    // 생성 시 한도 판정 — 소프트 캡 밖에 숨겨진 것까지 포함한 "보관 총량"(개인 프레임만)
    @Query("select count(f) from Frame f where f.user = :user and " + PERSONAL)
    long countPersonalByUser(@Param("user") User user);

    // 단건 조회 시 소프트 캡 판정 — 자기보다 최신인 개인 프레임 수
    @Query("select count(f) from Frame f where f.user = :user and f.createdAt > :createdAt and " + PERSONAL)
    long countPersonalByUserCreatedAfter(@Param("user") User user, @Param("createdAt") LocalDateTime createdAt);

    // FrameCountPort 어댑터용 — subscription의 사용량 API(개인 프레임만)
    @Query("select count(f) from Frame f where f.user.id = :userId and " + PERSONAL)
    long countPersonalByUserId(@Param("userId") Long userId);

    @Query("select f from Frame f left join fetch f.components where f.user.id = :userId")
    List<Frame> findAllWithComponentsByUserId(@Param("userId") Long userId);

    // 탈퇴 정리 대상 — 개인 공간(또는 공간이 없던 옛 행)의 프레임만. 조직 공간 프레임은 행사·다른 멤버가 쓰므로 남긴다
    @Query("""
            select f from Frame f left join fetch f.components
            where f.user.id = :userId and (f.workspaceId is null or f.workspaceId = :personalWorkspaceId)
            """)
    List<Frame> findPersonalWithComponents(@Param("userId") Long userId,
                                           @Param("personalWorkspaceId") Long personalWorkspaceId);

    @Modifying(clearAutomatically = true)
    @Query("delete from FrameComponent c where c.frame.id in :frameIds")
    void deleteComponentsByFrameIdIn(@Param("frameIds") List<Long> frameIds);

    @Modifying(clearAutomatically = true)
    @Query("delete from Frame f where f.id in :frameIds")
    void deleteByIdIn(@Param("frameIds") List<Long> frameIds);

    @Modifying(clearAutomatically = true)
    @Query("delete from FrameComponent c where c.frame.id in (select f.id from Frame f where f.user.id = :userId)")
    void deleteComponentsByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true)
    @Query("delete from Frame f where f.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
