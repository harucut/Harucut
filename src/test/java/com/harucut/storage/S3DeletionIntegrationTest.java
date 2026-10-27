package com.harucut.storage;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.batch.S3DeletionRetrier;
import com.harucut.storage.entity.S3Deletion;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3DeletionAttempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/*
 * S3 삭제 예약·재시도(V18 s3_deletion) — 커밋 뒤 첫 시도, 실패분 백오프 재시도, 상한, 같은 key 멱등.
 * 트랜잭션을 직접 커밋해야 AFTER_COMMIT 리스너가 돈다 — 테스트 트랜잭션을 쓰지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("S3 삭제 예약·재시도 — 통합")
class S3DeletionIntegrationTest {

    private static final String KEY = "uploads/users/AbCdEf12Gh/frames/a.png";
    private static final String OTHER = "uploads/users/AbCdEf12Gh/frames/b.png";

    @Autowired private S3Deleter s3Deleter;
    @Autowired private S3DeletionRepository repository;
    @Autowired private S3DeletionRetrier retrier;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private S3DeletionAttempt deletionAttempt;

    @MockitoBean private FileStorageService fileStorageService;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("커밋 뒤 지우고 예약 행도 지운다 — 롤백이면 예약도 삭제도 없다")
    void deletesAfterCommitAndClearsRow() {
        transaction.executeWithoutResult(status -> {
            s3Deleter.deleteAfterCommit(List.of(KEY));
            assertThat(repository.findAll()).hasSize(1);   // 같은 트랜잭션에 예약
            then(fileStorageService).shouldHaveNoInteractions();
        });
        then(fileStorageService).should().delete(KEY);
        assertThat(repository.findAll()).isEmpty();

        transaction.executeWithoutResult(status -> {
            s3Deleter.deleteAfterCommit(List.of(OTHER));
            status.setRollbackOnly();
        });
        then(fileStorageService).should(never()).delete(OTHER);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("커밋 뒤 삭제가 실패하면 행이 남는다(실패 1회, 다음 시도 5분 뒤) — 다른 key 는 지워진다")
    void failedDeleteLeavesRow() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);

        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY, OTHER)));

        then(fileStorageService).should().delete(OTHER);
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getS3Key()).isEqualTo(KEY);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).contains("S3 장애");
            assertThat(row.getNextAttemptAt()).isCloseTo(LocalDateTime.now().plusMinutes(5), within(Duration.ofMinutes(1)));
        });
    }

    @Test
    @DisplayName("재삭제(R5 D09): 커밋 뒤 지운 key 는 행을 남겨 재삭제 시각을 다음 차례로 옮기고, 그때 다시 지우면 행이 사라진다")
    void redeleteAfterWindow() {
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY), Duration.ofHours(6)));

        then(fileStorageService).should().delete(KEY);
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getRedeleteAt()).isNull();
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getNextAttemptAt()).isCloseTo(LocalDateTime.now().plusHours(6), within(Duration.ofMinutes(1)));
        });

        // 재삭제 시각이 됐다 — 늦게 도착한 실행이 다시 쓴 객체를 지우고 행을 정리한다
        jdbcTemplate.update("update s3_deletion set next_attempt_at = ?", LocalDateTime.now().minusMinutes(1));
        retrier.retryDue();
        then(fileStorageService).should(org.mockito.Mockito.times(2)).delete(KEY);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("#45 Codex: 두 인스턴스가 같은 행을 골랐을 때 먼저 지운 쪽이 재무장하면, 늦은 쪽(옛 일정)은 행을 지우지도 앞당기지도 않는다")
    void staleAttemptKeepsRearmedRow() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime redeleteAt = LocalDateTime.now().plusHours(6).withNano(0);
        String keyHash = OpaqueTokens.hash(KEY);
        transaction.executeWithoutResult(status -> {
            repository.schedule(KEY, keyHash, due, LocalDateTime.now());
            repository.armRedelete(keyHash, redeleteAt, LocalDateTime.now());
        });
        // 둘 다 같은 행(다음 시각 due)을 골랐다 — A 가 먼저 지우고 재무장
        assertThat(deletionAttempt.attempt(KEY, 0, due)).isTrue();
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getNextAttemptAt()).isEqualTo(redeleteAt);
            assertThat(row.getRedeleteAt()).isNull();
        });

        // B 도 지우는 데 성공 — 옛 일정(due)으로 본 행은 이제 없으니 재삭제 예약을 남긴다
        assertThat(deletionAttempt.attempt(KEY, 0, due)).isTrue();
        assertThat(repository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getNextAttemptAt()).isEqualTo(redeleteAt));

        // B 가 실패해도 재삭제 예약을 백오프(5분)로 앞당기지 않는다
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        assertThat(deletionAttempt.attempt(KEY, 0, due)).isFalse();
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getNextAttemptAt()).isEqualTo(redeleteAt);
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    @DisplayName("같은 key 를 여러 번 예약해도 행은 하나다(같은 트랜잭션·다른 트랜잭션)")
    void sameKeyScheduledOnce() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);

        transaction.executeWithoutResult(status -> {
            s3Deleter.deleteAfterCommit(List.of(KEY));
            s3Deleter.deleteAfterCommit(List.of(KEY));
        });
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));

        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getKeyHash()).isEqualTo(OpaqueTokens.hash(KEY)));
    }

    @Test
    @DisplayName("재시도: 차례가 된 행만 다시 지운다 — 성공하면 행 삭제, 다시 실패하면 횟수+1·더 긴 간격")
    void retriesDueRows() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(OTHER);
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY, OTHER)));

        assertThat(retrier.retryDue().tried()).isZero();   // 아직 다음 시각 전

        makeDue(KEY);
        makeDue(OTHER);
        willThrow(new IllegalStateException("또 장애")).given(fileStorageService).delete(OTHER);
        org.mockito.BDDMockito.willDoNothing().given(fileStorageService).delete(KEY);

        S3DeletionRetrier.Result result = retrier.retryDue();

        assertThat(result.tried()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getS3Key()).isEqualTo(OTHER);
            assertThat(row.getAttempts()).isEqualTo(2);
            assertThat(row.getNextAttemptAt()).isCloseTo(LocalDateTime.now().plusMinutes(15), within(Duration.ofMinutes(1)));
        });
    }

    @Test
    @DisplayName("상한(8회)에 닿은 행은 더 시도하지 않고 남겨 두며 abandoned 로 센다")
    void stopsAtMaxAttempts() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));
        jdbcTemplate.update("update s3_deletion set attempts = 7, next_attempt_at = ? where key_hash = ?",
                LocalDateTime.now().minusMinutes(1), OpaqueTokens.hash(KEY));

        S3DeletionRetrier.Result last = retrier.retryDue();
        assertThat(last.abandoned()).isEqualTo(1);
        assertThat(last.pending()).isZero();

        makeDue(KEY);
        assertThat(retrier.retryDue().tried()).isZero();   // 상한을 넘은 행은 다시 집지 않는다
        then(fileStorageService).should(times(2)).delete(KEY);   // 커밋 뒤 1번 + 마지막 재시도 1번
        assertThat(repository.findAll()).extracting(S3Deletion::getAttempts).containsExactly(8);
    }

    @Test
    @DisplayName("상한을 넘어 남긴 행을 다시 예약해도 그대로 남는다 — 첫 시도로 되살리지 않는다")
    void abandonedStaysAbandonedWhenRescheduled() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));
        jdbcTemplate.update("update s3_deletion set attempts = 8 where key_hash = ?", OpaqueTokens.hash(KEY));

        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));   // 보관 재삭제처럼 다시 예약

        then(fileStorageService).should(times(1)).delete(KEY);   // 처음 커밋 뒤 한 번뿐
        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getAttempts()).isEqualTo(8));
        assertThat(retrier.retryDue().abandoned()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도 중인 행을 다시 예약해도 실패 횟수·다음 시각이 그대로다")
    void pendingKeepsStateWhenRescheduled() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));
        LocalDateTime later = LocalDateTime.now().plusHours(3).withNano(0);
        jdbcTemplate.update("update s3_deletion set attempts = 3, next_attempt_at = ? where key_hash = ?", later, OpaqueTokens.hash(KEY));

        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));

        then(fileStorageService).should(times(1)).delete(KEY);
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getAttempts()).isEqualTo(3);
            assertThat(row.getNextAttemptAt()).isEqualTo(later);
        });
    }

    @Test
    @DisplayName("업로드 책임: 업로드 중 무효가 돼 다른 곳이 예약해도 미리 소모되지 않고, 커밋 전에 멈추면 책임 시간이 지나 지워진다")
    void heldKeySurvivesEarlyScheduleAndIsDeletedAfterLease() {
        transaction.executeWithoutResult(status -> s3Deleter.holdForUpload(KEY, Duration.ofHours(6)));
        // 업로드 중에 사진 삭제로 무효(ExportService.expire) — 일반 예약은 기존 책임 행에 막혀 첫 시도를 하지 않는다
        transaction.executeWithoutResult(status -> s3Deleter.deleteAfterCommit(List.of(KEY)));
        then(fileStorageService).should(never()).delete(KEY);
        // (업로드 완료 → 「무효 → 지금 지우기」 커밋 전에 프로세스가 멈춘다)
        assertThat(retrier.retryDue().tried()).isZero();   // 책임 시간 전

        makeDue(KEY);   // 책임 시간이 지났다
        assertThat(retrier.retryDue().deleted()).isEqualTo(1);
        then(fileStorageService).should().delete(KEY);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("업로드 책임: DONE 과 함께 풀면 남지 않고, 무효면 지금 지운다")
    void releaseOrDeleteHeld() {
        transaction.executeWithoutResult(status -> s3Deleter.holdForUpload(KEY, Duration.ofHours(6)));
        transaction.executeWithoutResult(status -> s3Deleter.release(KEY));
        assertThat(repository.findAll()).isEmpty();
        then(fileStorageService).should(never()).delete(KEY);

        transaction.executeWithoutResult(status -> s3Deleter.holdForUpload(OTHER, Duration.ofHours(6)));
        transaction.executeWithoutResult(status -> s3Deleter.deleteHeldAfterCommit(OTHER));
        then(fileStorageService).should().delete(OTHER);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 불러 리스너가 안 불려도 예약 행이 남아 배치가 지운다")
    void outsideTransactionIsPickedUpByRetrier() {
        s3Deleter.deleteAfterCommit(List.of(KEY));
        then(fileStorageService).shouldHaveNoInteractions();
        assertThat(repository.findAll()).hasSize(1);

        makeDue(KEY);
        assertThat(retrier.retryDue().deleted()).isEqualTo(1);
        then(fileStorageService).should().delete(KEY);
        assertThat(repository.findAll()).isEmpty();
    }

    private void makeDue(String key) {
        jdbcTemplate.update("update s3_deletion set next_attempt_at = ? where key_hash = ?",
                LocalDateTime.now().minusMinutes(1), OpaqueTokens.hash(key));
    }
}
