package com.harucut.media.compose;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// 합성 파이프라인의 계기판. 지금까지 이 경로에 지표가 하나도 없어서
// "즉시 접수가 통째로 죽어 있었다"를 아무도 눈치채지 못했다 — 202 는 정상으로 나갔고,
// 재실행 배치가 뒤늦게 처리해 최종 결과도 정상이었다. 접수 성공 수를 세고 있었다면
// 그 값이 0인 것이 첫날 보였다.
//
// 계측 코드를 한 클래스로 모으는 이유: 업무 코드가 MeterRegistry 를 직접 만지면
// 지표 이름과 태그가 여기저기 흩어져 조용히 어긋난다. 이름은 여기서만 정한다.
@Component
public class ComposeMetrics {

    // 모든 결과 카운터를 0으로 미리 등록한다. 프로메테우스는 한 번도 증가하지 않은
    // 카운터를 아예 안 보여준다 — "접수가 0건"과 "지표를 안 붙였다"가 구분되지 않는다.
    // 하필 그 둘의 구분이 필요한 순간이 장애 순간이다
    private final Counter accepted;
    private final Counter replayed;

    private final Counter dispatchClaimed;
    private final Counter dispatchSkipped;
    private final Counter dispatchFailed;
    private final Timer dispatchTimer;

    private final Counter notifiedDone;
    private final Counter notifiedFailed;
    private final Counter notifiedTransient;
    private final Counter notifiedUnusable;

    private final Counter rerunSubmitted;
    private final Counter rerunExhausted;

    // 게이지는 스크레이프 때마다 읽힌다. COUNT 쿼리를 그 시점에 돌리면 스크레이프 주기가
    // DB 부하를 정하게 되므로, 30초 재실행 주기가 갱신한 값을 들고만 있는다
    private final AtomicLong pendingBacklog = new AtomicLong(-1);

    public ComposeMetrics(MeterRegistry registry) {
        this.accepted = counter(registry, "harucut.compose.requests",
                "요청이 새 Job 으로 접수된 수", "outcome", "accepted");
        this.replayed = counter(registry, "harucut.compose.requests",
                "멱등키로 기존 Job 을 그대로 돌려준 수", "outcome", "replayed");

        this.dispatchClaimed = counter(registry, "harucut.compose.dispatch",
                "선점에 성공해 Lambda 로 실제 접수한 수", "result", "claimed");
        this.dispatchSkipped = counter(registry, "harucut.compose.dispatch",
                "이미 실행 중이거나 끝나 건너뛴 수", "result", "skipped");
        this.dispatchFailed = counter(registry, "harucut.compose.dispatch",
                "접수가 실패해 PENDING 으로 남긴 수", "result", "failed");
        this.dispatchTimer = Timer.builder("harucut.compose.dispatch.duration")
                .description("Lambda 접수 호출에 걸린 시간 — 요청 스레드가 붙잡히는 시간이다")
                .publishPercentileHistogram()
                .register(registry);

        this.notifiedDone = counter(registry, "harucut.compose.notifications",
                "완료 통지", "condition", "success");
        this.notifiedFailed = counter(registry, "harucut.compose.notifications",
                "영구 실패 통지 (RetriesExhausted)", "condition", "retries_exhausted");
        this.notifiedTransient = counter(registry, "harucut.compose.notifications",
                "일시적 실패로 보고 PENDING 을 유지한 통지", "condition", "transient");
        this.notifiedUnusable = counter(registry, "harucut.compose.notifications",
                "jobId 가 없어 무시한 통지", "condition", "no_job_id");

        this.rerunSubmitted = counter(registry, "harucut.compose.rerun",
                "재실행 배치가 다시 던진 Job 수", "result", "submitted");

        this.rerunExhausted = counter(registry, "harucut.compose.rerun",
                "재접수 상한을 넘겨 최종 실패로 끝낸 Job 수", "result", "exhausted");

        Gauge.builder("harucut.compose.pending", pendingBacklog, AtomicLong::doubleValue)
                .description("아직 끝나지 않은 Job 수 — 30초마다 갱신, 아직 못 잰 동안은 -1")
                .register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description,
                                   String tagKey, String tagValue) {
        return Counter.builder(name)
                .description(description)
                .tag(tagKey, tagValue)
                .register(registry);
    }

    // ── 접수 ──────────────────────────────

    public void requestAccepted() {
        accepted.increment();
    }

    public void requestReplayed() {
        replayed.increment();
    }

    // ── Lambda 접수 ──────────────────────────────

    // dispatch{result="claimed"} 가 0 이면 즉시 접수가 죽었다는 뜻이다.
    // 이 지표 하나가 이번 사고의 조기 경보였다
    public void dispatchClaimed() {
        dispatchClaimed.increment();
    }

    public void dispatchSkipped() {
        dispatchSkipped.increment();
    }

    public void dispatchFailed() {
        dispatchFailed.increment();
    }

    public void recordDispatch(long nanos) {
        dispatchTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    // ── 통지 ──────────────────────────────

    public void notifiedDone() {
        notifiedDone.increment();
    }

    public void notifiedFailed() {
        notifiedFailed.increment();
    }

    public void notifiedTransient() {
        notifiedTransient.increment();
    }

    public void notifiedWithoutJobId() {
        notifiedUnusable.increment();
    }

    // ── 재실행 ──────────────────────────────

    public void rerunSubmitted(int count) {
        rerunSubmitted.increment(count);
    }

    public void rerunExhausted() {
        rerunExhausted.increment();
    }

    public void recordPendingBacklog(long count) {
        pendingBacklog.set(count);
    }
}
