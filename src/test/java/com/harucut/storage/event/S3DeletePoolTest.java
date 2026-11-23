package com.harucut.storage.event;

import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;

/*
 * 삭제가 몰린 순간 — 커넥션 풀 크기만큼 삭제 예약이 동시에 커밋돼도 서로를 기다리며 멈추지 않는다(release PR #67 Codex).
 * AFTER_COMMIT 리스너는 트랜잭션 커넥션을 쥔 채 돈다. 거기서 예약 행 정리(REQUIRES_NEW)가 커넥션을 하나 더 달라고 하면
 * 모두가 서로를 기다리다 connection-timeout 뒤에 풀린다 — 그동안 풀 전체가 멈춰 상관없는 요청도 굶는다(ComposeDispatchPoolTest 와 같은 꼴).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=2000"
})
@ActiveProfiles("test")
@DisplayName("S3 삭제 — 커넥션 풀 포화")
class S3DeletePoolTest {

    private static final int POOL_SIZE = 3;

    @Autowired private S3Deleter s3Deleter;
    @Autowired private S3DeletionRepository repository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("풀 크기만큼 동시에 커밋해도 응답이 커넥션을 기다리지 않고, 모두 지워져 예약 행도 남지 않는다")
    void simultaneousDeletesDoNotStall() throws Exception {
        CyclicBarrier allHoldConnections = new CyclicBarrier(POOL_SIZE);
        List<String> keys = new ArrayList<>();
        List<CompletableFuture<Long>> deletes = new ArrayList<>();
        long startedAt = System.nanoTime();
        try (ExecutorService requests = Executors.newFixedThreadPool(POOL_SIZE)) {
            for (int i = 0; i < POOL_SIZE; i++) {
                String key = "uploads/users/AbCdEf12Gh/frames/pool-" + UUID.randomUUID() + ".png";
                keys.add(key);
                deletes.add(CompletableFuture.supplyAsync(() -> {
                    transactionTemplate.executeWithoutResult(status -> {
                        s3Deleter.deleteAfterCommit(List.of(key));   // 예약 행 INSERT — 이 트랜잭션이 커넥션을 쥔다
                        arrive(allHoldConnections);   // 셋 다 커넥션을 쥔 채 함께 커밋한다
                    });
                    return System.nanoTime() - startedAt;
                }, requests));
            }

            long slowest = 0;
            for (CompletableFuture<Long> delete : deletes) {
                slowest = Math.max(slowest, delete.get(30, TimeUnit.SECONDS));
            }
            assertThat(Duration.ofNanos(slowest)).as("커밋이 connection-timeout 만큼 멈췄다").isLessThan(Duration.ofMillis(1500));
        }
        keys.forEach(key -> then(fileStorageService).should(timeout(5000)).delete(key));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(repository.findAll()).isEmpty());
    }

    private static void arrive(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
