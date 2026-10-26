package com.harucut.export.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.export.enums.ExportStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 행사 사진 묶음 다운로드 작업 하나 (ADR-0008 §4). 상태 전이는 저장소의 조건부 UPDATE 로 한다 — 워커와 무효화가 겹칠 수 있다
@Entity
@Table(name = "export_job", indexes = {
        @Index(name = "idx_export_job_event_requester", columnList = "event_id, requested_by_user_id"),
        @Index(name = "idx_export_job_status", columnList = "status")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExportJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "export_job_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExportStatus status;

    @Column(name = "photo_count", nullable = false)
    private int photoCount;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "failure_reason")
    private String failureReason;

    private ExportJob(Long eventId, String eventPublicId, Long requestedByUserId, int photoCount) {
        this.publicId = PublicIds.generate();
        this.eventId = eventId;
        this.requestedByUserId = requestedByUserId;
        this.photoCount = photoCount;
        this.status = ExportStatus.PENDING;
        this.s3Key = "uploads/events/" + eventPublicId + "/exports/" + publicId + ".zip";
    }

    public static ExportJob pending(Long eventId, String eventPublicId, Long requestedByUserId, int photoCount) {
        return new ExportJob(eventId, eventPublicId, requestedByUserId, photoCount);
    }
}
