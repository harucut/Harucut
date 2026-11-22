package com.harucut.user.repository;

import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.enums.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndEmail(Provider provider, String email);

    // 탈퇴 유예가 지난 계정 — 하드 삭제(UserDeletionScheduler)의 대상. 처리하면 조건에서 빠지므로 id 를 먼저 모아 둔다
    @Query("select u.id from User u where u.userStatus = com.harucut.user.enums.UserStatus.DELETED_REQUESTED "
            + "and u.deleteRequestedAt < :threshold order by u.id")
    List<Long> findExpiredExitTargetIds(@Param("threshold") LocalDateTime threshold);

    Optional<User> findByPublicId(String publicId);

    // id 만 — 엔티티를 올리지 않는다. 뒤이은 잠금 읽기(lockByPublicId)가 이 요청 안에서 낡은 엔티티를 돌려받지 않게
    @Query("select u.id from User u where u.publicId = :publicId")
    Optional<Long> findIdByPublicId(@Param("publicId") String publicId);

    // 이 회원들 중 탈퇴를 신청하지 않은 사람 — DELETED_REQUESTED 는 유예가 끝나면 멤버십째 지워진다. 회원 id 순 잠금 읽기(FOR UPDATE)라
    // 스냅샷이 아니라 다른 요청이 막 커밋한 탈퇴 신청까지 본다(WorkspaceService.hasRemainingOwnerOtherThan)
    @Query(value = "select user_id from users where user_id in (:ids) "
            + "and user_status not in ('DELETED_REQUESTED', 'DELETED') order by user_id for update", nativeQuery = true)
    List<Long> lockStayingIds(@Param("ids") List<Long> ids);

    // 소셜 계정은 같은 이메일이 제공자마다 따로 있다
    List<User> findAllByEmailOrderByIdAsc(String email);

    // 지금 역할·상태만 — 관리 경로의 재확인(AccountStateGuard). 엔티티를 올리지 않아 같은 요청의 잠금 읽기가 낡은 값을 보지 않는다
    @Query("select u.userRole as role, u.userStatus as status from User u where u.publicId = :publicId")
    Optional<AccountState> findStateByPublicId(@Param("publicId") String publicId);

    interface AccountState {
        UserRole getRole();

        UserStatus getStatus();
    }

    // 사용자 단위 상한(개인 프레임 개수)을 동시 생성에도 지킬 때 — SELECT … FOR UPDATE(행 잠금만)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.publicId = :publicId")
    Optional<User> lockByPublicId(@Param("publicId") String publicId);

    boolean existsByProviderAndEmail(Provider provider, String email);

    Optional<User> findByProviderAndProviderId(Provider provider, String providerId);
}
