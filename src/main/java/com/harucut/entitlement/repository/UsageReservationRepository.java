package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.UsageReservation;
import com.harucut.entitlement.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UsageReservationRepository extends JpaRepository<UsageReservation, Long> {

    // 작업의 예약 — 행사 배분이면 부모·자식 두 행
    List<UsageReservation> findAllByComposeJobId(Long composeJobId);

    long countByEntitlementIdAndStatus(Long entitlementId, ReservationStatus status);

    // 종결 — RESERVED 인 것만 한 번. COMMITTED·RELEASED 중 먼저 온 하나만 1 을 받는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UsageReservation r set r.status = :status where r.composeJobId = :jobId "
            + "and r.status = com.harucut.entitlement.enums.ReservationStatus.RESERVED")
    int settle(@Param("jobId") Long jobId, @Param("status") ReservationStatus status);
}
