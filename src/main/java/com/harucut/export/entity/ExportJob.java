package com.harucut.export.entity;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.export.enums.ExportScope;
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
        @Index(name = "idx_export_job_status", columnList = "status"),
        @Index(name = "idx_export_job_scope_requester", columnList = "workspace_id, scope, requested_by_user_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExportJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "export_job_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    // 행사 하나 묶음(EVENT)일 때만 — 여러 행사 묶음은 비어 있다
    @Column(name = "event_id")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExportScope scope;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    // PROJECT 일 때만
    @Column(name = "project_id")
    private Long projectId;

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

    private ExportJob(ExportScope scope, Long workspaceId, Long projectId, Long eventId, String keyRoot, Long requestedByUserId,
                      int photoCount) {
        this.publicId = PublicIds.generate();
        this.scope = scope;
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.eventId = eventId;
        this.requestedByUserId = requestedByUserId;
        this.photoCount = photoCount;
        this.status = ExportStatus.PENDING;
        this.s3Key = keyRoot + "/exports/" + publicId + ".zip";
    }

    public static ExportJob pending(Long workspaceId, Long eventId, String eventPublicId, Long requestedByUserId, int photoCount) {
        return new ExportJob(ExportScope.EVENT, workspaceId, null, eventId, "uploads/events/" + eventPublicId, requestedByUserId, photoCount);
    }

    /**
     * 내려놓은 묶음을 다시 만들 때 올릴 새 key — 같은 폴더, 시도마다 다른 꼬리. 앞 시도의 업로드 책임(삭제 예약)이 언제 집혀도
     * 새 업로드에 닿지 않는다(재시도 배치는 행을 잠그지 않고 읽어 지운다)
     */
    public static String retryKey(String key) {
        String base = key.replaceFirst("(-r[A-Za-z0-9_-]{8})?\\.zip$", "");
        return base + "-r" + OpaqueTokens.generate().substring(0, 8) + ".zip";
    }

    /** 여러 행사 묶음 — key 는 공간 아래(행사 하나에 속하지 않는다) */
    public static ExportJob pendingMulti(ExportScope scope, Long workspaceId, String workspacePublicId, Long projectId,
                                         Long requestedByUserId, int photoCount) {
        return new ExportJob(scope, workspaceId, projectId, null, "uploads/workspaces/" + workspacePublicId, requestedByUserId, photoCount);
    }
}
