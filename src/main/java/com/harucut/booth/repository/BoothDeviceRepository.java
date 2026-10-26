package com.harucut.booth.repository;

import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.enums.BoothDeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoothDeviceRepository extends JpaRepository<BoothDevice, Long> {

    Optional<BoothDevice> findByPublicId(String publicId);

    Optional<BoothDevice> findByTokenHash(String tokenHash);

    List<BoothDevice> findAllByEventIdOrderByIdAsc(Long eventId);

    long countByEventIdAndStatusNot(Long eventId, BoothDeviceStatus status);
}
