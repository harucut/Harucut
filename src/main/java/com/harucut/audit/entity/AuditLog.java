package com.harucut.audit.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 운영 행동 기록 (ADR-0008 §2-3). 쓰기만 하고 고치지 않으므로 updated_at 이 없다 — BaseEntity 를 쓰지 않는다
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_workspace_id", columnList = "workspace_id, audit_log_id"),
        @Index(name = "idx_audit_log_event_id", columnList = "event_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "event_id")
    private Long eventId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", length = 30)
    private String targetType;

    @Column(name = "target_id", length = 40)
    private String targetId;

    @Column(length = 2000)
    private String detail;

    public AuditLog(LocalDateTime createdAt, Long actorUserId, Long workspaceId, Long eventId, String action,
                    String targetType, String targetId, String detail) {
        this.createdAt = createdAt;
        this.actorUserId = actorUserId;
        this.workspaceId = workspaceId;
        this.eventId = eventId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }
}
