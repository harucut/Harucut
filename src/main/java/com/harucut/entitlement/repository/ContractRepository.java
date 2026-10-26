package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EvidenceKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    Optional<Contract> findByPublicId(String publicId);

    // 상태 전이 — 읽은 상태(from)일 때만. 두 관리자가 동시에 ACTIVE 로 바꿔도 한쪽만 1 을 받아 이용권이 두 번 생기지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Contract c set c.status = :to where c.id = :id and c.status = :from")
    int transition(@Param("id") Long id, @Param("from") ContractStatus from, @Param("to") ContractStatus to);

    boolean existsByEvidenceKindAndEvidenceReference(EvidenceKind evidenceKind, String evidenceReference);


    List<Contract> findAllByOrderByIdDesc(Pageable pageable);

    Optional<Contract> findByEvidenceKindAndEvidenceReference(EvidenceKind evidenceKind, String evidenceReference);
}
