package com.harucut.result.entity;

import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 개인 결과 링크(/r#<token>)의 서버 쪽. 첫 조회로 소모하지 않는다 — 함께 찍은 여러 명이 같은 QR 을 스캔한다
@Entity
@Table(name = "result_access", indexes = @Index(name = "idx_result_access_compose_job_id", columnList = "compose_job_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultAccess extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_access_id")
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "compose_job_id", nullable = false)
    private Long composeJobId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public ResultAccess(String tokenHash, Long composeJobId, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.composeJobId = composeJobId;
        this.expiresAt = expiresAt;
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    public boolean expiredAt(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }
}
