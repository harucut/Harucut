package com.harucut.media.compose;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

/*
 * 행사 피크의 합성 접수 — 커넥션 풀 크기만큼 접수가 동시에 커밋돼도 서로를 기다리며 멈추지 않는다.
 * AFTER_COMMIT 리스너는 트랜잭션 커넥션을 쥔 채 돈다. 거기서 선점(REQUIRES_NEW)이 커넥션을 하나 더 달라고 하면 모두가
 * 서로를 기다리다 connection-timeout 뒤에 실패한다 — 풀 전체가 그동안 멈추고 작업은 재실행 배치까지 밀린다.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=2000"
})
@ActiveProfiles("test")
@DisplayName("합성 접수 — 커넥션 풀 포화")
class ComposeDispatchPoolTest {

    private static final int POOL_SIZE = 3;

    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private LambdaComposeExecutor composeExecutor;

    @Test
    @DisplayName("풀 크기만큼 동시에 커밋해도 응답이 커넥션을 기다리지 않고, 모든 작업이 실행기까지 간다")
    void simultaneousAcceptsDoNotStall() throws Exception {
        CyclicBarrier allHoldConnections = new CyclicBarrier(POOL_SIZE);
        List<CompletableFuture<Long>> accepts = new ArrayList<>();
        long startedAt = System.nanoTime();
        for (int i = 0; i < POOL_SIZE; i++) {
            accepts.add(CompletableFuture.supplyAsync(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    Long jobId = saveJob();
                    eventPublisher.publishEvent(new ComposeRequestedEvent(jobId, spec(), keys(), "uploads/r.jpg", "uploads/t.jpg",
                            ImageFormat.JPEG));
                    await(allHoldConnections);   // 셋 다 커넥션을 쥔 채 함께 커밋한다
                });
                return System.nanoTime() - startedAt;
            }));
        }

        long slowest = 0;
        for (CompletableFuture<Long> accept : accepts) {
            slowest = Math.max(slowest, accept.get(30, TimeUnit.SECONDS));
        }

        assertThat(Duration.ofNanos(slowest)).as("접수가 connection-timeout 만큼 멈췄다").isLessThan(Duration.ofMillis(1500));
        then(composeExecutor).should(timeout(5000).times(POOL_SIZE)).execute(any(ComposeRequestedEvent.class));
    }

    private Long saveJob() {
        User user = userRepository.save(UserFixtures.localUser("pool-" + UUID.randomUUID() + "@harucut.com", "pw"));
        return composeJobRepository.save(ComposeJob.create(user, 1L, "idem-" + UUID.randomUUID(), keys(), spec())).getId();
    }

    private static List<String> keys() {
        return List.of("uploads/s1.jpg", "uploads/s2.jpg", "uploads/s3.jpg", "uploads/s4.jpg");
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#112233"), FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false), List.of());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
