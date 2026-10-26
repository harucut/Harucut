package com.harucut.media.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.converter.ComposeSpecConverter;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

// 네컷 합성 작업 한 건의 장부 행 — 이 테이블이 내구성 있는 큐다.
// 서버가 죽어도 PENDING 행이 남고, 오래된 PENDING은 재실행된다
// (docs/adr-0001-compose-result-channel.md)
@Entity
@Table(name = "compose_job",
        // ComposeRerunScheduler가 30초마다 findStalled를 돈다:
        //   WHERE status = PENDING AND (started_at IS NULL OR started_at < ?)
        // 이 테이블은 단조 증가한다 — 완료된 Job을 지우는 배치가 없다(deleteByUserId는 탈퇴용).
        // 인덱스가 없으면 그 풀스캔 비용이 누적 행 수를 따라 계속 자란다.
        // 지금 규모에서 느려서 거는 게 아니라, 큰 테이블에 나중에 거는 편이 훨씬 비싸서 건다.
        //
        // ⚠️ ddl-auto가 운영은 update(Flyway 편입 전), load는 validate인데 Hibernate의 validate는 인덱스를
        //    검사하지 않는다(테이블·컬럼·타입만 본다). 이 애노테이션만으로는 기존 DB에
        //    인덱스가 생기지 않는다 — local(update)로 새로 만들거나 CREATE INDEX를 직접 쳐야 한다
        indexes = {
                @Index(name = "idx_compose_job_status_started_at", columnList = "status, started_at"),
                @Index(name = "idx_compose_job_user_id", columnList = "user_id")
        },
        // 멱등은 "같은 주체의 같은 요청"이다. 주체는 개인(U:{userId})과 행사 참가자(P:{participantId})다 —
        // user_id 만으로는 User 가 없는 참가자 작업의 멱등을 걸 수 없다 (ADR-0007 §6)
        uniqueConstraints =
        @UniqueConstraint(name = "uk_compose_job_scope_idempotency",
                columnNames = {"idempotency_scope", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ComposeJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "compose_job_id")
    private Long id;

    // 개인 작업의 소유자. 행사 참가자 작업은 NULL — 참가자는 participant 쪽으로 이어진다(P2A)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // 서버가 정규화한 멱등 범위 — 요청자가 고를 수 없다. U:{userId} / P:{participantId}
    @Column(name = "idempotency_scope", nullable = false, length = 80)
    private String idempotencyScope;

    // 결과·썸네일을 쓸 폴더(끝에 / 포함). 재실행이 User 를 다시 읽지 않고 같은 key 를 만들게 생성 때 고정한다
    @Column(name = "storage_root", nullable = false, length = 255)
    private String storageRoot;

    // ── 행사 참가자 작업의 범위(P2A). 개인 작업은 전부 NULL ──
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "capture_run_id")
    private Long captureRunId;

    @Column(name = "experience_version_id")
    private Long experienceVersionId;

    @Column(name = "frame_version_id")
    private Long frameVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ComposeStatus status;

    // 클라이언트가 요청마다 새로 만드는 UUID — (user, key) unique가 더블클릭·재시도 중복 합성을 막는다.
    // 멱등은 "같은 사용자의 같은 요청"이라는 뜻이라 unique 범위도 사용자 단위다
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    // 추적용 값 — FK를 걸지 않는다. 스펙이 스냅샷이라 프레임이 수정·삭제돼도 Job은 자급자족한다
    @Column(name = "frame_id", nullable = false)
    private Long frameId;

    // "네컷은 정확히 4장"을 코드가 아니라 스키마(not null 4컬럼)로 보장한다. 순서 = 슬롯 순서
    @Column(name = "source_key_1", nullable = false, length = 512)
    private String sourceKey1;

    @Column(name = "source_key_2", nullable = false, length = 512)
    private String sourceKey2;

    @Column(name = "source_key_3", nullable = false, length = 512)
    private String sourceKey3;

    @Column(name = "source_key_4", nullable = false, length = 512)
    private String sourceKey4;

    // 요청 시점 프레임의 스냅샷 — 실행·재실행은 프레임을 다시 읽지 않고 이것만 본다
    @Convert(converter = ComposeSpecConverter.class)
    @Column(name = "spec_json", nullable = false, length = 8000)
    private ComposeSpec spec;

    @Column(name = "result_key", length = 512)
    private String resultKey;

    // 성공 시 만들어진 UserMedia의 id — FK 없이 값만. 미디어는 이후 독립적으로 삭제될 수 있다
    @Column(name = "media_id")
    private Long mediaId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    // 행사 사진함의 삭제 요청 — 목록·ZIP·결과 링크에서 빠진다(ADR-0008 §3)
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 보관 기한이 지나 결과 객체를 지운 시각(ADR-0008 §7)
    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    private ComposeJob(User user, String idempotencyScope, String storageRoot, Long frameId,
                       String idempotencyKey, List<String> sourceKeys, ComposeSpec spec) {
        this.user = user;
        this.idempotencyScope = idempotencyScope;
        this.storageRoot = storageRoot;
        this.status = ComposeStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.frameId = frameId;
        this.sourceKey1 = sourceKeys.get(0);
        this.sourceKey2 = sourceKeys.get(1);
        this.sourceKey3 = sourceKeys.get(2);
        this.sourceKey4 = sourceKeys.get(3);
        this.spec = spec;
    }

    public static ComposeJob create(User user, Long frameId, String idempotencyKey,
                                    List<String> sourceKeys, ComposeSpec spec) {
        if (user == null) {
            throw new IllegalArgumentException("합성 작업에는 소유자가 필요하다");
        }
        if (sourceKeys == null || sourceKeys.size() != 4
                || sourceKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("원본 사진 key는 정확히 4개여야 한다");
        }
        return new ComposeJob(user, personalScope(user.getId()), S3Keys.userRoot(user.getPublicId()),
                frameId, idempotencyKey, sourceKeys, spec);
    }

    /**
     * 행사 참가자 작업. User 가 없고, 멱등 범위는 P:{participantId}, 결과는 회차 폴더에 쓴다.
     * frameId 는 추적용으로 발행본의 원본 프레임 id 를 넣는다 — 합성은 spec 스냅샷만 본다.
     */
    public static ComposeJob forParticipant(Long participantId, String storageRoot, Long frameId, String idempotencyKey,
                                            List<String> sourceKeys, ComposeSpec spec, Long eventId,
                                            Long captureRunId, Long experienceVersionId, Long frameVersionId) {
        if (sourceKeys == null || sourceKeys.size() != 4
                || sourceKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("원본 사진 key는 정확히 4개여야 한다");
        }
        ComposeJob job = new ComposeJob(null, participantScope(participantId), storageRoot, frameId,
                idempotencyKey, sourceKeys, spec);
        job.eventId = eventId;
        job.participantId = participantId;
        job.captureRunId = captureRunId;
        job.experienceVersionId = experienceVersionId;
        job.frameVersionId = frameVersionId;
        return job;
    }

    public static String participantScope(Long participantId) {
        return "P:" + participantId;
    }

    public static String personalScope(Long userId) {
        return "U:" + userId;
    }

    // 완료·실패 전이는 엔티티 메서드가 아니라 ComposeJobRepository 의 조건부 UPDATE 다.
    // 메모리의 status 를 보고 고치면 동시에 온 두 통지가 둘 다 PENDING 을 보고 둘 다 이긴다.
    // failure_reason 컬럼(기본 255자)에 맞춰 자르는 규칙만 여기 남긴다
    public static String truncateFailureReason(String reason) {
        return reason != null && reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    // Lambda 페이로드 조립용 — 슬롯 순서 그대로
    public List<String> sourceKeys() {
        return List.of(sourceKey1, sourceKey2, sourceKey3, sourceKey4);
    }

    // 사진함·묶음·결과 링크에서 더는 보이지 않는가 — 삭제 요청이나 보관 종료
    public boolean isDeleted() {
        return deletedAt != null || purgedAt != null;
    }
}
