package com.harucut.storage.event;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.S3DeletionAttempt;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// S3 삭제는 롤백이 없으므로 DB가 진실로 확정된 뒤에만 실행한다.
// 실패의 최악은 고아 파일(스토리지 비용) — 사용자에게 보이는 장애가 아니다. (Phase 8 결정)
// 실패해도 예약 행(s3_deletion)이 남아 S3DeletionRetrier 가 다시 지운다
//
// 요청 스레드에서 지우지 않고 전용 스레드로 넘긴다. AFTER_COMMIT 리스너는 트랜잭션 커넥션을 아직 쥔 채 돈다 —
// 거기서 예약 행 정리(REQUIRES_NEW)가 커넥션을 하나 더 달라고 하면, 풀 크기만큼 삭제가 동시에 커밋될 때 모두가 서로를 기다리다
// connection-timeout 뒤에 풀린다(ComposeWorker 와 같은 이유, S3DeletePoolTest 가 재현한다).
// 큐가 차면 버린다 — 예약 행이 남아 재시도 배치가 첫 백오프 뒤에 지운다. 유실이 아니다
@Slf4j
@Component
public class S3DeleteListener {

    private static final int QUEUE_CAPACITY = 1_000;

    private final S3DeletionRepository repository;
    private final S3DeletionAttempt deletionAttempt;
    private final Executor executor;

    @Autowired
    public S3DeleteListener(S3DeletionRepository repository, S3DeletionAttempt deletionAttempt) {
        this(repository, deletionAttempt, newExecutor());
    }

    S3DeleteListener(S3DeletionRepository repository, S3DeletionAttempt deletionAttempt, Executor executor) {
        this.repository = repository;
        this.deletionAttempt = deletionAttempt;
        this.executor = executor;
    }

    // 빈으로 두지 않는다 — Executor 빈이 생기면 Boot 의 기본 applicationTaskExecutor 가 빠진다(ComposeWorker 와 같은 선택)
    private static Executor newExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "s3-delete");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> log.warn("[S3 삭제] 대기열이 가득 차 첫 시도를 건너뛴다 — 재시도 배치가 지운다"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();   // 남은 첫 시도는 예약 행으로 남아 재시도 배치가 지운다
        }
    }

    // 트랜잭션 밖에서 발행된 이벤트는 (fallbackExecution 기본 false) 리스너가 아예 안 불린다 —
    // 그때도 예약 행은 남아 재시도 배치가 지운다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(S3DeleteEvent event) {
        executor.execute(() -> event.keys().forEach(this::attempt));
    }

    // 큐에서 기다린 사이 재시도 배치가 같은 행을 먼저 다뤘을 수 있다 — 배치처럼 지금 행의 횟수·일정으로 시도해
    // 그쪽이 남긴 재삭제 예약·실패 기록을 덮지 않는다. 행이 없으면 끝난 일이다(배치가 지웠거나 업로드 책임이 풀렸다)
    private void attempt(String key) {
        try {
            repository.findByKeyHash(OpaqueTokens.hash(key))
                    .ifPresent(row -> deletionAttempt.attempt(row.getS3Key(), row.getAttempts(), row.getNextAttemptAt()));
        } catch (RuntimeException e) {
            // key 단위로 삼킨다: 하나가 실패해도 나머지는 지운다
            log.error("S3 삭제 시도 실패 — 예약 행이 남아 다시 시도한다: {}", key, e);
        }
    }
}
