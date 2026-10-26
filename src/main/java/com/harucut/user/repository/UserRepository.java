package com.harucut.user.repository;

import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndEmail(Provider provider, String email);

    Optional<User> findByPublicId(String publicId);

    // 사용자 단위 상한(개인 프레임 개수)을 동시 생성에도 지킬 때 — SELECT … FOR UPDATE(행 잠금만)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.publicId = :publicId")
    Optional<User> lockByPublicId(@Param("publicId") String publicId);

    boolean existsByProviderAndEmail(Provider provider, String email);

    Optional<User> findByProviderAndProviderId(Provider provider, String providerId);
}
