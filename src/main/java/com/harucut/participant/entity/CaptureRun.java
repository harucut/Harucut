package com.harucut.participant.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.participant.enums.CaptureRunStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 한 팀이 네컷 결과 하나를 만드는 이용 과정. 시작할 때 받은 경험 버전으로 끝까지 간다 (R5 4·6절)
@Entity
@Table(name = "capture_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_capture_run_active_booth_device", columnNames = "active_booth_device_id"),
        indexes = @Index(name = "idx_capture_run_participant_id", columnList = "participant_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureRun extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "capture_run_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "experience_version_id", nullable = false)
    private Long experienceVersionId;

    @Column(name = "booth_device_id")
    private Long boothDeviceId;

    // 부스 회차가 ACTIVE 인 동안만 채운다 — 유니크라 기기당 활성 회차 하나
    @Column(name = "active_booth_device_id")
    private Long activeBoothDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaptureRunStatus status;

    // 부스 화면이 늦게 온 이전 회차 결과를 새 손님에게 보이지 않게 대조하는 값
    @Column(name = "state_version", nullable = false)
    private int stateVersion;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    private CaptureRun(Long participantId, Long eventId, Long experienceVersionId, Long boothDeviceId,
                       LocalDateTime expiresAt) {
        this.publicId = PublicIds.generate();
        this.participantId = participantId;
        this.eventId = eventId;
        this.experienceVersionId = experienceVersionId;
        this.boothDeviceId = boothDeviceId;
        this.activeBoothDeviceId = boothDeviceId;
        this.status = CaptureRunStatus.ACTIVE;
        this.stateVersion = 1;
        this.expiresAt = expiresAt;
    }

    public static CaptureRun start(Long participantId, Long eventId, Long experienceVersionId, Long boothDeviceId,
                                   LocalDateTime expiresAt) {
        return new CaptureRun(participantId, eventId, experienceVersionId, boothDeviceId, expiresAt);
    }

    public boolean activeAt(LocalDateTime now) {
        return status == CaptureRunStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public void close(LocalDateTime now) {
        this.status = CaptureRunStatus.CLOSED;
        this.activeBoothDeviceId = null;
        this.closedAt = now;
        this.stateVersion++;
    }

    public void expire(LocalDateTime now) {
        this.status = CaptureRunStatus.EXPIRED;
        this.activeBoothDeviceId = null;
        this.closedAt = now;
        this.stateVersion++;
    }
}
