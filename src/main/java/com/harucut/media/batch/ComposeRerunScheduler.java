package com.harucut.media.batch;

import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeMetrics;
import com.harucut.media.compose.ComposeRequestedEvent;
import com.harucut.media.compose.ComposeWorker;
import com.harucut.media.service.ComposeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// 통지가 끝내 오지 않은 Job을 다시 던진다. 세 경우가 여기 걸린다.
//   ① 커밋했는데 invoke 전에 서버가 죽었다 — 이중 쓰기라 SQS로도 안 막힌다
//   ② 통지의 condition이 EventAgeExceeded 였다 — 소비자가 일부러 PENDING으로 뒀다
//   ③ 통지 처리가 계속 실패해 DLQ로 샜다
// staleAfter가 Lambda 이벤트 수명(5분)보다 넉넉해야 한다 — 아직 살아 있는 이벤트를
// 다시 던지면 같은 작업이 두 번 돈다 (선점이 데이터는 지켜주지만 호출값은 두 번 나간다)
@Slf4j
@Component
public class ComposeRerunScheduler {

    private final ComposeService composeService;
    private final ComposeWorker composeWorker;
    private final ComposeMetrics metrics;
    private final Duration staleAfter;
    private final int batchSize;

    public ComposeRerunScheduler(ComposeService composeService, ComposeWorker composeWorker,
                                 ComposeMetrics metrics,
                                 @Value("${compose.stale-after:10m}") Duration staleAfter,
                                 @Value("${compose.rerun.batch-size:20}") int batchSize) {
        this.composeService = composeService;
        this.composeWorker = composeWorker;
        this.metrics = metrics;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    // fixedDelay: 이전 실행이 끝난 뒤부터 센다 — 한 주기가 길어져도 겹치지 않는다
    @Scheduled(fixedDelayString = "${compose.rerun.interval:30s}")
    public void run() {
        // 적체 게이지를 여기서 갱신한다 — 이 배치가 이미 30초마다 도는 유일한 주기라
        // 세는 주기를 새로 만들 필요가 없다. 조회가 실패해도 재실행은 계속돼야 한다
        try {
            metrics.recordPendingBacklog(composeService.countPending());
        } catch (Exception e) {
            log.warn("[합성 재실행] 적체 수 조회 실패 — 게이지만 낡는다", e);
        }

        // 예산을 다 쓴 작업을 먼저 끝낸다 — 예약을 돌려주고 원본을 지운다. 한 건이 실패해도 나머지와 재접수는 계속 간다
        for (ComposeService.ExpiredDispatch expired : composeService.findDispatchExpired(staleAfter, batchSize)) {
            try {
                if (composeService.failJob(expired.jobId(), "앱 재접수 상한 초과(횟수·총 기한)",
                        ComposeFailureCode.DISPATCH_EXHAUSTED, expired.attemptNo())) {
                    metrics.rerunExhausted();
                    log.warn("[합성 재실행] 재접수 상한 초과 — 최종 실패: jobId={} attempt={}", expired.jobId(), expired.attemptNo());
                }
            } catch (Exception e) {
                log.error("[합성 재실행] 최종 실패 처리 실패 — 다음 주기에 다시 본다: jobId={}", expired.jobId(), e);
            }
        }

        List<ComposeRequestedEvent> stalled = composeService.findStalled(staleAfter, batchSize);

        if (stalled.isEmpty()) {
            return;
        }

        // 접수가 수십 ms라 스케줄러 스레드에서 순서대로 밀어 넣는다 — 거부될 큐가 없다.
        // 접수 실패는 rerun 안에서 삼켜지고 Job은 PENDING으로 남아 다음 주기에 다시 걸린다
        stalled.forEach(composeWorker::rerun);
        metrics.rerunSubmitted(stalled.size());
        log.info("[합성 재실행] {}건 재투입", stalled.size());
    }
}
