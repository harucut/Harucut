package com.harucut.support.mysql;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.batch.S3DeletionRetrier;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;

/*
 * S3 삭제 예약(V18) — INSERT IGNORE 멱등·실패 기록·재시도 조회가 실제 MySQL 에서 돈다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("S3 삭제 예약·재시도 — 실제 MySQL")
class S3DeletionMysqlTest {

    private static final String KEY = "uploads/users/AbCdEf12Gh/frames/mysql.png";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private S3Deleter s3Deleter;
    @Autowired private S3DeletionRepository repository;
    @Autowired private S3DeletionRetrier retrier;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("같은 key 두 번 예약은 한 행, 커밋 뒤 실패는 기록되고 차례가 되면 재시도로 지워진다")
    void scheduleFailRetry() {
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(KEY);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            s3Deleter.deleteAfterCommit(List.of(KEY));
            s3Deleter.deleteAfterCommit(List.of(KEY));
        });

        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1));

        willDoNothing().given(fileStorageService).delete(KEY);
        jdbcTemplate.update("update s3_deletion set next_attempt_at = ? where key_hash = ?",
                LocalDateTime.now().minusMinutes(1), OpaqueTokens.hash(KEY));
        assertThat(retrier.retryDue().deleted()).isEqualTo(1);
        assertThat(repository.findAll()).isEmpty();
        then(fileStorageService).should(org.mockito.Mockito.atLeast(2)).delete(KEY);
    }
}
