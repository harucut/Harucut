package com.harucut.media.policy;

import java.time.LocalDateTime;

public interface MediaSubscriptionPolicy {

    LocalDateTime resolveHistoryCutoff(Long userId);

    // 그 시각에 만든 기록이 지금 요금제로 주인에게 보이는가 — 아래 assert 와 같은 판정(경계 포함)
    boolean isHistoryAccessible(Long userId, LocalDateTime createdAt);

    void assertHistoryAccessible(Long userId, LocalDateTime createdAt);
}
