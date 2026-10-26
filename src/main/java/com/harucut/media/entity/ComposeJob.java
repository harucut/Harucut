package com.harucut.media.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.converter.ComposeSpecConverter;
import com.harucut.media.enums.ComposeStatus;
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
        // ⚠️ ddl-auto가 운영은 none, load는 validate인데 Hibernate의 validate는 인덱스를
        //    검사하지 않는다(테이블·컬럼·타입만 본다). 이 애노테이션만으로는 기존 DB에
        //    인덱스가 생기지 않는다 — local(update)로 새로 만들거나 CREATE INDEX를 직접 쳐야 한다
        indexes = @Index(name = "idx_compose_job_status_started_at",
                columnList = "status, started_at"),
        uniqueConstraints =
        @UniqueConstraint(name = "uk_compose_job_user_idempotency",
                columnNames = {"user_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ComposeJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "compose_job_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

    private ComposeJob(User user, Long frameId, String idempotencyKey,
                       List<String> sourceKeys, ComposeSpec spec) {
        this.user = user;
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
        return new ComposeJob(user, frameId, idempotencyKey, sourceKeys, spec);
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
}
