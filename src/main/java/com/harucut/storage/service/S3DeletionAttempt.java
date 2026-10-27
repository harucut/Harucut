package com.harucut.storage.service;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.repository.S3DeletionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S3 객체 하나를 지우고 예약 행을 정리한다 — 커밋 뒤 리스너의 첫 시도와 재시도 배치가 같이 쓴다.
 * S3 를 먼저 지우고 행을 나중에 지운다: 그 사이에 죽어도 행이 남아 다시 지운다(없는 key 삭제는 멱등).
 * 실패하면 횟수와 다음 시각(백오프)을 남기고, 상한에 닿으면 더 시도하지 않고 남겨 둔다.
 */
@Slf4j
@Component
public class S3DeletionAttempt {

    // n 번째 실패 뒤 다음 시도까지 — 마지막 값이 상한 간격이다. 기본 상한 8회면 첫 예약부터 약 이틀
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1),
            Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));

    private final FileStorageService fileStorageService;
    private final S3DeletionRepository repository;
    private final TransactionTemplate newTransaction;
    private final Clock clock;
    private final int maxAttempts;

    public S3DeletionAttempt(FileStorageService fileStorageService, S3DeletionRepository repository,
                             PlatformTransactionManager transactionManager, Clock clock,
                             @Value("${storage.deletion.max-attempts:8}") int maxAttempts) {
        this.fileStorageService = fileStorageService;
        this.repository = repository;
        // 커밋 뒤(AFTER_COMMIT) 단계에서도 불린다 — 끝난 트랜잭션에 참여하지 않게 늘 새 트랜잭션
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    /** 커밋 뒤 첫 시도 — 방금 만든 행이라 고른 일정이 없다(재시도 배치는 첫 백오프 전에 이 행을 고르지 않는다) */
    public boolean attempt(String key, int previousFailures) {
        return attempt(key, previousFailures, null);
    }

    /**
     * @param previousFailures 지금까지 실패한 횟수(첫 시도면 0)
     * @param seenNextAttemptAt 재시도 배치가 행을 고를 때 본 다음 시각 — 행 정리(재무장·삭제·실패 기록)는 그 일정 그대로인 행에만 한다.
     *                          배치는 잠금 없이 고르고 여러 인스턴스가 돌므로, 다른 인스턴스가 같은 행을 먼저 지우고 재삭제로 재무장했으면
     *                          (다음 시각이 바뀜) 늦은 쪽은 행을 건드리지 않는다 — 재삭제 예약을 지우거나 백오프로 앞당기지 않게(R5 D09)
     * @return 지웠으면 true
     */
    public boolean attempt(String key, int previousFailures, LocalDateTime seenNextAttemptAt) {
        String keyHash = OpaqueTokens.hash(key);
        try {
            fileStorageService.delete(key);
        } catch (RuntimeException e) {
            int failures = previousFailures + 1;
            LocalDateTime now = LocalDateTime.now(clock);
            String reason = truncate(e.getClass().getSimpleName() + ": " + e.getMessage());
            update(() -> repository.recordFailure(keyHash, previousFailures, seenNextAttemptAt, now.plus(backoff(failures)),
                    reason, now));
            if (failures >= maxAttempts) {
                log.error("[S3 삭제] 재시도 상한({}회)에 닿아 남겨 둔다 — 고아 객체: {} ({})", maxAttempts, key, reason);
            } else {
                log.warn("[S3 삭제] 실패 {}회 — 다시 시도한다: {} ({})", failures, key, reason);
            }
            return false;
        }
        // 재삭제가 걸린 key 는 행을 남겨 그 시각에 한 번 더 지운다 — 늦게 도착한 실행이 다시 만든 객체를 치운다(R5 D09)
        LocalDateTime now = LocalDateTime.now(clock);
        update(() -> {
            if (repository.rearmForRedelete(keyHash, seenNextAttemptAt, now) == 0) {
                repository.deleteIfSchedule(keyHash, seenNextAttemptAt);
            }
        });
        return true;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    static Duration backoff(int failures) {
        return BACKOFF.get(Math.min(failures, BACKOFF.size()) - 1);
    }

    // 행 정리 실패는 삼킨다 — 커밋 뒤 예외가 새면 「저장은 됐는데 500」이 된다. 행이 남으면 배치가 다시 본다
    private void update(Runnable work) {
        try {
            newTransaction.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.error("[S3 삭제] 예약 행 갱신 실패 — 다음 재시도에서 다시 본다", e);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
