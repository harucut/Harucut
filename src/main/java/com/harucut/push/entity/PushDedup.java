package com.harucut.push.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 한 번만 보낼 푸시의 기록(예: 같은 구독 기간의 만료 예고). 쓰기는 PushDedupRepository.recordOnce(INSERT IGNORE)로만 한다 —
 * 여러 인스턴스의 배치가 같은 날 돌아도 한쪽만 1 을 받아 보낸다.
 */
@Entity
@Table(name = "push_dedup",
        uniqueConstraints = @UniqueConstraint(name = "uk_push_dedup_key", columnNames = "dedup_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDedup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "push_dedup_id")
    private Long id;

    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
