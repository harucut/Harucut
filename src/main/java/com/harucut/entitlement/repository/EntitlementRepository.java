package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.EntitlementStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    // 예약 — 지금 유효하고 남은 한도가 있을 때만 reserved+1. 0 이면 한도 도달.
    // 유효기간도 여기서 본다: 후보를 읽은 뒤(또는 그 스냅샷 이후) 계약이 끝나 validTo 가 줄었으면 끝난 이용권에 예약하지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.reserved = e.reserved + 1 where e.id = :id "
            + "and e.status = com.harucut.entitlement.enums.EntitlementStatus.ACTIVE "
            + "and e.validFrom <= :now and e.validTo > :now "
            + "and e.used + e.reserved + 1 <= e.composeQuota")
    int reserve(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.used = e.used + 1, e.reserved = e.reserved - 1 where e.id = :id and e.reserved > 0")
    int commit(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.reserved = e.reserved - 1 where e.id = :id and e.reserved > 0")
    int release(@Param("id") Long id);

    // 행사 배분 — 부모의 활성 자식 배분량 합을 늘린다. 합이 부모 한도를 넘으면 0(ENT-005). 범위를 잠그지 않는 원자 갱신(행 잠금만)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.allocatedQuota = e.allocatedQuota + :amount where e.id = :id "
            + "and e.status = com.harucut.entitlement.enums.EntitlementStatus.ACTIVE "
            + "and e.allocatedQuota + :amount <= e.composeQuota")
    int allocate(@Param("id") Long id, @Param("amount") int amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.allocatedQuota = e.allocatedQuota - :amount where e.id = :id and e.allocatedQuota >= :amount")
    int deallocate(@Param("id") Long id, @Param("amount") int amount);

    // 배분량 바꾸기 — 이미 사용·예약한 양 아래로는 0(ENT-006)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.composeQuota = :quota where e.id = :id and e.used + e.reserved <= :quota")
    int resize(@Param("id") Long id, @Param("quota") int quota);

    List<Entitlement> findAllByParentEntitlementId(Long parentEntitlementId);

    // 부모 행만 잠근다(PK 행 잠금) — 배분량을 줄일 때 자식보다 부모를 먼저 잡아 잠금 순서(부모 → 자식)를 지킨다
    @Query(value = "select entitlement_id from entitlement where entitlement_id = :id for update", nativeQuery = true)
    List<Long> lockById(@Param("id") Long id);

    // 한도 알림 — 확정 사용량이 그 비율 이상이고 아직 기록하지 않았을 때만 1(동시에 확정돼도 한 번)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.notified80At = :now where e.id = :id and e.notified80At is null "
            + "and e.composeQuota > 0 and e.used * 100 >= e.composeQuota * 80")
    int markAlert80(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.notified95At = :now where e.id = :id and e.notified95At is null "
            + "and e.composeQuota > 0 and e.used * 100 >= e.composeQuota * 95")
    int markAlert95(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.notified100At = :now where e.id = :id and e.notified100At is null "
            + "and e.composeQuota > 0 and e.used >= e.composeQuota")
    int markAlert100(@Param("id") Long id, @Param("now") LocalDateTime now);
}
