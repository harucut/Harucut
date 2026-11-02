package com.harucut.media.compose;

import com.harucut.media.service.ComposeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// 합성 "접수". AFTER_COMMIT이라 Job 행이 보이는 상태에서만 시작한다.
//
// 요청 스레드에서 부르지 않고 전용 스레드로 넘긴다. AFTER_COMMIT 리스너는 트랜잭션 커넥션을 아직 쥔 채 돈다 —
// 거기서 선점(REQUIRES_NEW)이 커넥션을 하나 더 달라고 하면, 풀 크기(기본 10)만큼 접수가 동시에 커밋될 때 모두가 서로를 기다리다
// connection-timeout(기본 30초) 뒤에 실패한다. 그동안 풀이 통째로 멈춰 로그인·결제까지 막힌다(ComposeDispatchPoolTest 가 재현한다).
// 큐가 차면 버린다 — Job 이 PENDING 으로 남아 ComposeRerunScheduler 가 30초 안에 다시 던진다. 유실이 아니다.
//
// 이 클래스는 Job을 끝내지 않는다. DONE/FAILED는 Lambda Destination 통지를 받은
// ComposeResultConsumer가 찍는다 (docs/adr-0001-compose-result-channel.md)
@Slf4j
@Component
public class ComposeWorker {

    private static final int DISPATCH_THREADS = 4;
    private static final int DISPATCH_QUEUE = 1000;

    private final ComposeExecutor composeExecutor;
    private final ComposeService composeService;
    private final ComposeMetrics metrics;
    private final Duration staleAfter;
    private final Executor dispatcher;

    @Autowired
    public ComposeWorker(ComposeExecutor composeExecutor, ComposeService composeService,
                         ComposeMetrics metrics,
                         @Value("${compose.stale-after:10m}") Duration staleAfter) {
        this(composeExecutor, composeService, metrics, staleAfter, newDispatcher());
    }

    ComposeWorker(ComposeExecutor composeExecutor, ComposeService composeService, ComposeMetrics metrics,
                  Duration staleAfter, Executor dispatcher) {
        this.composeExecutor = composeExecutor;
        this.composeService = composeService;
        this.metrics = metrics;
        this.staleAfter = staleAfter;
        this.dispatcher = dispatcher;
    }

    // 빈으로 두지 않는다 — Executor 빈이 생기면 Boot 의 기본 applicationTaskExecutor 가 빠진다(ExportWorker·PushDispatcher 와 같은 선택)
    private static Executor newDispatcher() {
        AtomicInteger number = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(DISPATCH_THREADS, DISPATCH_THREADS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DISPATCH_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "compose-dispatch-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> log.warn("[합성] 접수 큐가 찼다 — 재실행 배치가 이어 간다"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void stop() {
        if (dispatcher instanceof ExecutorService service) {
            service.shutdown();   // 남은 접수는 PENDING 으로 남아 다음 인스턴스의 재실행 배치가 던진다
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ComposeRequestedEvent event) {
        dispatcher.execute(() -> execute(event));
    }

    // 재실행 스케줄러가 부르는 문. 이벤트가 아니라 직접 호출이라 트랜잭션 리스너를 안 탄다
    public void rerun(ComposeRequestedEvent event) {
        execute(event);
    }

    private void execute(ComposeRequestedEvent event) {
        Integer attemptNo = composeService.claim(event.jobId(), staleAfter);
        if (attemptNo == null) {
            metrics.dispatchSkipped();
            log.debug("이미 실행 중이거나 끝난 Job — 건너뛴다: jobId={}", event.jobId());
            return;
        }
        long startedAt = System.nanoTime();
        try {
            composeExecutor.execute(event.withAttempt(attemptNo));
            metrics.dispatchClaimed();
        } catch (Exception e) {
            metrics.dispatchFailed();
            // 이 catch가 두 가지를 막는다.
            //
            // (1) FAILED로 적지 않는다 — 접수가 실패한 것이라 Job은 손도 안 댄 상태다.
            //     PENDING으로 두면 stale-after 뒤 ComposeRerunScheduler가 다시 던진다.
            //     여기서 failJob을 부르면 재시도 가능한 실패가 영구 손실이 된다.
            // (2) 예외를 밖으로 내보내지 않는다 — 전용 스레드·재실행 배치를 죽이지 않게.
            log.error("[합성] Lambda 접수 실패 — PENDING 유지: jobId={}", event.jobId(), e);
        } finally {
            // 성공·실패 둘 다 잰다
            metrics.recordDispatch(System.nanoTime() - startedAt);
        }
    }
}
