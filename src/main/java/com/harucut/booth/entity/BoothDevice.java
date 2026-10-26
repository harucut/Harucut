package com.harucut.booth.entity;

import com.harucut.booth.enums.BoothDeviceStatus;
import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 행사장에 둔 공용 촬영 기기. 회원이 아니다 — 연결 코드로 받은 기기 토큰이 자격이다 (ADR-0007 §7)
@Entity
@Table(name = "booth_device", indexes = @Index(name = "idx_booth_device_event_id", columnList = "event_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoothDevice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booth_device_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BoothDeviceStatus status;

    @Column(name = "token_hash", unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "credential_version", nullable = false)
    private int credentialVersion;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    private BoothDevice(Long workspaceId, Long eventId, String name) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.eventId = eventId;
        this.name = name;
        this.status = BoothDeviceStatus.PENDING_PAIRING;
    }

    public static BoothDevice pending(Long workspaceId, Long eventId, String name) {
        return new BoothDevice(workspaceId, eventId, name);
    }

    // 연결 성공 — 새 토큰으로 갈아 끼운다. 이전 토큰(있었다면)은 해시가 바뀌어 더는 맞지 않는다
    public void pair(String tokenHash) {
        this.tokenHash = tokenHash;
        this.credentialVersion++;
        this.status = BoothDeviceStatus.ACTIVE;
    }

    public void revoke() {
        this.tokenHash = null;
        this.status = BoothDeviceStatus.REVOKED;
    }

    public boolean isRevoked() {
        return status == BoothDeviceStatus.REVOKED;
    }

    public void seen(LocalDateTime now) {
        this.lastSeenAt = now;
    }
}
