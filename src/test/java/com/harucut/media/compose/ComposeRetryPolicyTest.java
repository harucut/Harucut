package com.harucut.media.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeRetryPolicyTest {

    private final ComposeRetryPolicy policy = new ComposeRetryPolicy(4, Duration.ofMinutes(90), Duration.ofMinutes(10), Duration.ofMinutes(30));

    @Test
    @DisplayName("던질 때마다 두 배로 늘고 상한이 있다 — 지터는 0~10% 만 더한다")
    void backoffDoublesWithCapAndJitter() {
        for (int i = 0; i < 200; i++) {
            assertThat(policy.backoffAfter(1)).isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11));
            assertThat(policy.backoffAfter(2)).isBetween(Duration.ofMinutes(20), Duration.ofMinutes(22));
            assertThat(policy.backoffAfter(3)).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(33));
            assertThat(policy.backoffAfter(100)).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(33));
        }
    }

    @Test
    @DisplayName("지터가 실제로 퍼진다 — 같은 때 던진 작업이 같은 때 몰리지 않게")
    void jitterSpreads() {
        long distinct = java.util.stream.IntStream.range(0, 50).mapToObj(i -> policy.backoffAfter(1)).distinct().count();
        assertThat(distinct).isGreaterThan(1);
    }
}
