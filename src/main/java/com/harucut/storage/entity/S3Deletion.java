package com.harucut.storage.entity;

import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 지울 S3 객체 하나 — 커밋 뒤 삭제에 성공하면 지운다. 남아 있으면 재시도 배치가 다시 지운다(V18)
@Entity
@Table(name = "s3_deletion",
        uniqueConstraints = @UniqueConstraint(name = "uk_s3_deletion_key_hash", columnNames = "key_hash"),
        indexes = @Index(name = "idx_s3_deletion_next_attempt_at", columnList = "next_attempt_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class S3Deletion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "s3_deletion_id")
    private Long id;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Column(name = "key_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String keyHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
