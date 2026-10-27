package com.harucut.media.compose;

import com.harucut.media.service.ComposeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

// 합성 "접수". AFTER_COMMIT이라 Job 행이 보이는 상태에서만 시작한다.
// 비동기 invoke가 수십 ms에 끝나므로 요청 스레드에서 그대로 부른다 — 스레드풀도 큐도 없다.
//
// 이 클래스는 Job을 끝내지 않는다. DONE/FAILED는 Lambda Destination 통지를 받은
// ComposeResultConsumer가 찍는다 (docs/adr-0001-compose-result-channel.md)
@Slf4j
@Component
public class ComposeWorker {

    private final ComposeExecutor composeExecutor;
    private final ComposeService composeService;
    private final ComposeMetrics metrics;
    private final Duration staleAfter;

    public ComposeWorker(ComposeExecutor composeExecutor, ComposeService composeService,
                         ComposeMetrics metrics,
                         @Value("${compose.stale-after:10m}") Duration staleAfter) {
        this.composeExecutor = composeExecutor;
        this.composeService = composeService;
        this.metrics = metrics;
        this.staleAfter = staleAfter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ComposeRequestedEvent event) {
        execute(event);
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
            // (2) 예외를 밖으로 내보내지 않는다 — @Async가 없어진 뒤로 이 메서드는
            //     요청 스레드에서 돈다. AFTER_COMMIT에서 던진 예외는 이미 커밋된
            //     트랜잭션 위로 올라가서, 202가 확정된 요청을 500으로 뒤집는다.
            log.error("[합성] Lambda 접수 실패 — PENDING 유지: jobId={}", event.jobId(), e);
        } finally {
            // 성공·실패 둘 다 잰다. 이 시간이 곧 요청 스레드가 붙잡히는 시간이다
            metrics.recordDispatch(System.nanoTime() - startedAt);
        }
    }
}
