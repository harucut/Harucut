package com.harucut.booth.entity;

import com.harucut.booth.enums.PairingStatus;
import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 부스 기기 연결 거래 하나. 코드 XXXX-XXXX 의 앞 4자(lookup)로 거래를 찾고 뒤 4자(비밀)를 해시로 대조한다 —
// 그래야 틀린 시도를 "어느 거래의 실패"로 셀 수 있다(코드 전체를 해시로만 두면 틀린 코드는 아무 거래에도 닿지 않는다)
@Entity
@Table(name = "booth_pairing", indexes = {
        @Index(name = "idx_booth_pairing_lookup", columnList = "lookup"),
        @Index(name = "idx_booth_pairing_booth_device_id", columnList = "booth_device_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoothPairing extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booth_pairing_id")
    private Long id;

    @Column(name = "booth_device_id", nullable = false)
    private Long boothDeviceId;

    @Column(nullable = false, length = 4, columnDefinition = "char(4)")
    private String lookup;

    @Column(name = "secret_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String secretHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PairingStatus status;

    public BoothPairing(Long boothDeviceId, String lookup, String secretHash, LocalDateTime expiresAt) {
        this.boothDeviceId = boothDeviceId;
        this.lookup = lookup;
        this.secretHash = secretHash;
        this.expiresAt = expiresAt;
        this.status = PairingStatus.OPEN;
    }

    public boolean openAt(LocalDateTime now) {
        return status == PairingStatus.OPEN && now.isBefore(expiresAt);
    }

    /** 틀린 비밀. 한도에 닿으면 거래를 닫는다 — 운영자가 새 코드를 발급해야 한다 */
    public void fail(int maxAttempts) {
        this.failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            this.status = PairingStatus.CLOSED;
        }
    }

    public boolean lockedOut(int maxAttempts) {
        return status == PairingStatus.CLOSED && failedAttempts >= maxAttempts;
    }

    public void close() {
        if (status == PairingStatus.OPEN) {
            this.status = PairingStatus.CLOSED;
        }
    }
}
