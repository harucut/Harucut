package com.harucut.media.compose;

import org.springframework.beans.factory.annotation.Autowired;
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
    // 횟수·총 기한·간격 상한 — 설정으로 바꾼 적이 없어 상수다. 간격의 시작(stale-after)만 설정에서 온다(Lambda 이벤트 수명에서 파생)
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration TOTAL_DEADLINE = Duration.ofMinutes(90);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final int maxAttempts;
    private final Duration totalDeadline;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    @Autowired
    public ComposeRetryPolicy(@Value("${compose.stale-after:10m}") Duration baseBackoff) {
        this(MAX_ATTEMPTS, TOTAL_DEADLINE, baseBackoff, MAX_BACKOFF);
    }

    // 시험이 값을 바꾼다
    public ComposeRetryPolicy(int maxAttempts, Duration totalDeadline, Duration baseBackoff, Duration maxBackoff) {
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
