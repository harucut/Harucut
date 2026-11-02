package com.harucut.media.compose;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 앱 재접수 정책(R5 O05). 접수 실패·스로틀·통지 유실이면 작업이 PENDING 으로 남는데, 끝없이 다시 던지면 Lambda 비용이 나가고 이용권 예약이
 * 돌아오지 않는다. 횟수·총 기한을 넘기면 최종 실패(DISPATCH_EXHAUSTED)로 끝낸다.
 *
 * <p>기본값: 약 0·10·30·60분에 던지고 90분에 끝낸다. AWS 내부 재시도(이벤트 수명 안)와 겹치지 않게 간격의 시작을 stale-after 로 둔다 —
 * 실제 event-invoke-config 를 확인하면 값을 맞출 것(R5 481줄).
 */
@Component
public class ComposeRetryPolicy {

    private static final double JITTER = 0.1;

    private final int maxAttempts;
    private final Duration totalDeadline;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public ComposeRetryPolicy(@Value("${compose.rerun.max-attempts:4}") int maxAttempts,
                              @Value("${compose.rerun.total-deadline:90m}") Duration totalDeadline,
                              @Value("${compose.stale-after:10m}") Duration baseBackoff,
                              @Value("${compose.rerun.max-backoff:30m}") Duration maxBackoff) {
        this.maxAttempts = maxAttempts;
        this.totalDeadline = totalDeadline;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration totalDeadline() {
        return totalDeadline;
    }

    /** n 번째로 던진 뒤 다음 시도까지 — 두 배씩 늘고 상한이 있다. 지터는 여러 작업이 같은 때 몰리지 않게(0~10%) */
    public Duration backoffAfter(int attemptNo) {
        int doublings = Math.min(Math.max(attemptNo - 1, 0), 16);
        Duration backoff = baseBackoff.multipliedBy(1L << doublings);
        Duration capped = backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
        return capped.plusMillis((long) (capped.toMillis() * JITTER * ThreadLocalRandom.current().nextDouble()));
    }
}
