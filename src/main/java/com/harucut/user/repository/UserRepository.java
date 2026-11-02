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

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndEmail(Provider provider, String email);

    Optional<User> findByPublicId(String publicId);

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
