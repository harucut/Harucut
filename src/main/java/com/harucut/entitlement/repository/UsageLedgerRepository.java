package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.UsageLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UsageLedgerRepository extends JpaRepository<UsageLedger, Long> {

    List<UsageLedger> findAllByComposeJobIdOrderByIdAsc(Long composeJobId);
}
