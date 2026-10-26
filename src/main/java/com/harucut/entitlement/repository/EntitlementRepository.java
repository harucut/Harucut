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

    // 부모 행만 잠근다(PK 행 잠금) — 배분·배분량 변경·회수가 같은 부모 아래에서 줄을 서고, 잠금 순서(부모 → 자식)를 지킨다
    @Query(value = "select entitlement_id from entitlement where entitlement_id = :id for update", nativeQuery = true)
    List<Long> lockById(@Param("id") Long id);

    // 활성 자식의 현재 한도 — 잠금 읽기(부모를 잡은 뒤). 잠금 전에 읽은 값으로 차이를 계산하면 동시 변경이 부모 합을 두 번 줄인다
    @Query(value = "select compose_quota from entitlement where entitlement_id = :id and status = 'ACTIVE' for update", nativeQuery = true)
    List<Integer> lockActiveQuota(@Param("id") Long id);

    // 부모의 활성 자식 — 잠금 읽기(부모 회수에서 부모를 쥔 뒤). 이 경로는 그 범위에 insert 하지 않아 gap lock 이 다른 부모와 교착하지 않는다.
    // 배분 만들기에서는 쓰지 않는다 — 빈 범위의 gap lock 을 다른 부모의 첫 배분과 함께 잡고 서로의 insert 를 기다려 교착된다(#39)
    @Query(value = "select entitlement_id from entitlement where parent_entitlement_id = :parentId and status = 'ACTIVE' for update",
            nativeQuery = true)
    List<Long> lockActiveChildIds(@Param("parentId") Long parentId);

    // 배분할 부모 — 행 잠금 + 지금 유효한가(활성·기간 안). 비면 배분할 수 없다
    @Query(value = "select entitlement_id from entitlement where entitlement_id = :id and status = 'ACTIVE' "
            + "and valid_from <= :now and valid_to > :now for update", nativeQuery = true)
    List<Long> lockValidParent(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 계약 종료 — 그 계약의 이용권(배분 포함)을 지금 끝낸다. UPDATE 는 현재 읽기라, 미리 골라 두면 놓치는 방금 커밋된 배분까지 끝낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.validTo = :now where e.contractId = :contractId and e.validTo > :now")
    int endByContract(@Param("contractId") Long contractId, @Param("now") LocalDateTime now);

    // 회수 — ACTIVE 일 때만 한 번. 동시에 두 번 회수해도 한쪽만 1(부모 카운터를 한 번만 돌려준다)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entitlement e set e.status = com.harucut.entitlement.enums.EntitlementStatus.REVOKED, e.revokedAt = :now, "
            + "e.revokeReason = :reason where e.id = :id and e.status = com.harucut.entitlement.enums.EntitlementStatus.ACTIVE")
    int revokeIfActive(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("reason") String reason);

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
