package com.harucut.result.repository;

import com.harucut.result.entity.ResultAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResultAccessRepository extends JpaRepository<ResultAccess, Long> {

    Optional<ResultAccess> findByTokenHash(String tokenHash);
}
