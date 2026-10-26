package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.Contract;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    Optional<Contract> findByPublicId(String publicId);

    List<Contract> findAllByOrderByIdDesc(Pageable pageable);
}
