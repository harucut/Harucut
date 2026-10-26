package com.harucut.asset.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 매일 03:00. 지우기가 멱등이라 서버 두 대가 함께 돌아도 결과가 같다 — 분산 락을 두지 않는다
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetOrphanCleanupScheduler {

    private final AssetOrphanCleaner cleaner;

    @Scheduled(cron = "${storage.asset.orphan-cleanup.cron:0 0 3 * * *}")
    public void run() {
        try {
            int deleted = cleaner.run();
            log.info("[자산 정리] 미참조 자산 {}건 삭제", deleted);
        } catch (Exception e) {
            log.error("[자산 정리] 실패 — 다음 실행이 이어서 본다", e);
        }
    }
}
