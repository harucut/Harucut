package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.EntitlementStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    Optional<Entitlement> findByPublicId(String publicId);

    List<Entitlement> findAllByOrderByIdDesc(Pageable pageable);

    List<Entitlement> findAllByContractId(Long contractId);

    // 행사에 적용될 후보 — 그 행사 범위이거나 그 공간 범위. 유효기간은 호출자가 본다
    @Query("select e from Entitlement e where e.status = :status and e.workspaceId = :workspaceId "
            + "and (e.eventId = :eventId or e.eventId is null)")
    List<Entitlement> findCandidates(@Param("workspaceId") Long workspaceId, @Param("eventId") Long eventId,
                                     @Param("status") EntitlementStatus status);

    // 예약 — 남은 한도가 있을 때만 reserved+1. 0 이면 한도 도달
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.reserved = e.reserved + 1 where e.id = :id "
            + "and e.status = com.harucut.entitlement.enums.EntitlementStatus.ACTIVE "
            + "and e.used + e.reserved + 1 <= e.composeQuota")
    int reserve(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.used = e.used + 1, e.reserved = e.reserved - 1 where e.id = :id and e.reserved > 0")
    int commit(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.reserved = e.reserved - 1 where e.id = :id and e.reserved > 0")
    int release(@Param("id") Long id);
}
