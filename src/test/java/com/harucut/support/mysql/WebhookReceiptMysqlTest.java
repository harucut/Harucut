package com.harucut.support.mysql;

import com.harucut.payment.webhook.WebhookService;
import com.harucut.storage.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * P2B-7 — 같은 웹훅이 동시에 두 번 오면, 늦은 쪽은 앞쪽 커밋까지 기다린 뒤 0행을 받고 정상 커밋해야 한다.
 * 예전처럼 유니크 위반을 예외로 받으면 그 트랜잭션이 롤백 전용이 되어 커밋에서 500 이 났다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("웹훅 수신 기록 동시 중복 — 실제 MySQL")
class WebhookReceiptMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private WebhookService webhookService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("앞쪽이 커밋하기 전에 들어온 같은 webhook-id 는 기다렸다가 false 로 정상 커밋된다")
    void concurrentDuplicateCommitsCleanly() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String body = "{\"type\":\"BillingKey.Issued\"}";
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            boolean handled = webhookService.handle("portone", "msg_race", body);
            firstInserted.countDown();
            await(releaseFirst);
            return handled;
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
        // 앞쪽이 아직 커밋 전 — 늦은 쪽은 유니크 키 잠금에서 기다린다
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() ->
                transaction.execute(status -> webhookService.handle("portone", "msg_race", body)));
        Thread.sleep(500);
        assertThat(second).isNotDone();
        releaseFirst.countDown();

        assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(20, TimeUnit.SECONDS)).isFalse();   // 예외(롤백 전용 → UnexpectedRollbackException) 없이
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
