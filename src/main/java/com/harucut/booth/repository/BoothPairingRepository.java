package com.harucut.booth.repository;

import com.harucut.booth.entity.BoothPairing;
import com.harucut.booth.enums.PairingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoothPairingRepository extends JpaRepository<BoothPairing, Long> {

    // 같은 lookup 의 거래 — 최신 것부터. 열린 거래끼리 lookup 이 겹치지 않게 발급하지만 닫힌 옛 거래와는 겹칠 수 있다
    List<BoothPairing> findAllByLookupOrderByIdDesc(String lookup);

    boolean existsByLookupAndStatus(String lookup, PairingStatus status);

    List<BoothPairing> findAllByBoothDeviceIdAndStatus(Long boothDeviceId, PairingStatus status);

    // 성공 1회 원자 소비 — 동시에 같은 코드로 두 기기가 들어와도 한쪽만 1을 받는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoothPairing p set p.status = com.harucut.booth.enums.PairingStatus.REDEEMED "
            + "where p.id = :id and p.status = com.harucut.booth.enums.PairingStatus.OPEN")
    int redeem(@Param("id") Long id);
}
