package com.harucut.participant.repository;

import com.harucut.participant.entity.CaptureRun;
import com.harucut.participant.enums.CaptureRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CaptureRunRepository extends JpaRepository<CaptureRun, Long> {

    Optional<CaptureRun> findByPublicId(String publicId);

    Optional<CaptureRun> findFirstByParticipantIdAndStatusOrderByIdDesc(Long participantId, CaptureRunStatus status);

    Optional<CaptureRun> findByActiveBoothDeviceId(Long boothDeviceId);
}
