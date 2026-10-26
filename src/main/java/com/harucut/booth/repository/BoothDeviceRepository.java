package com.harucut.booth.repository;

import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.enums.BoothDeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BoothDeviceRepository extends JpaRepository<BoothDevice, Long> {

    Optional<BoothDevice> findByPublicId(String publicId);

    Optional<BoothDevice> findByTokenHash(String tokenHash);

    List<BoothDevice> findAllByEventIdOrderByIdAsc(Long eventId);

    // 회수되지 않은 기기 — 잠금 읽기라 공간 잠금 뒤 최신 커밋을 센다(스냅샷 읽기는 동시에 만든 기기를 놓친다)
    @Query(value = "select booth_device_id from booth_device where event_id = :eventId and status <> 'REVOKED' for update", nativeQuery = true)
    List<Long> lockUnrevokedIds(@Param("eventId") Long eventId);
}
