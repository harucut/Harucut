package com.harucut.booth.repository;

import com.harucut.booth.entity.BoothPairing;
import com.harucut.booth.enums.PairingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    // 틀린 비밀 1회 — 열린 거래에서만 행에서 바로 센다. 겹친 오답이 서로의 증가를 덮거나, 먼저 소비·잠금된 거래를 되살리지 않는다.
    // 한도에 닿으면 같은 문장에서 닫는다. status 를 먼저 쓴다 — MySQL 은 SET 을 왼쪽부터 계산해 뒤 식이 바뀐 failed_attempts 를 본다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BoothPairing p set p.status = case when p.failedAttempts + 1 >= :maxAttempts "
            + "then com.harucut.booth.enums.PairingStatus.CLOSED else p.status end, p.failedAttempts = p.failedAttempts + 1 "
            + "where p.id = :id and p.status = com.harucut.booth.enums.PairingStatus.OPEN")
    int fail(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    // 지금 상태를 잠금 읽기로 — 스냅샷은 다른 요청이 막 닫은 거래를 아직 열린 것으로 보여 준다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BoothPairing p where p.id = :id")
    Optional<BoothPairing> lockById(@Param("id") Long id);
}
