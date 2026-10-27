package com.harucut.push.entity;

import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 푸시 받을 기기(Expo 푸시 토큰). 토큰 하나는 한 계정에만 묶인다 — 다른 계정이 같은 토큰을 등록하면 옮겨 간다.
 * 쓰기는 DeviceTokenRepository 의 upsert 로만 한다(동시 등록에도 유니크 위반 예외가 없다).
 */
@Entity
@Table(
        name = "device_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_token_token", columnNames = "token"),
        indexes = @Index(name = "idx_device_token_user_id", columnList = "user_id, last_registered_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_token_id")
    private Long id;

    // 연관 대신 스칼라 id(terms_agreement 와 같은 선택). DB FK 는 있다(V28)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DevicePlatform platform;

    // 계정당 상한을 넘으면 이 값이 가장 오래된 기기부터 지운다
    @Column(name = "last_registered_at", nullable = false)
    private LocalDateTime lastRegisteredAt;

    /** 로그에 토큰 전체를 남기지 않는다 — 앞 8자만. */
    public static String mask(String token) {
        return token == null ? null : token.substring(0, Math.min(8, token.length())) + "…";
    }
}
