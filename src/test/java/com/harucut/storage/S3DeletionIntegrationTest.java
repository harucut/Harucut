package com.harucut.storage;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.batch.S3DeletionRetrier;
import com.harucut.storage.entity.S3Deletion;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
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
