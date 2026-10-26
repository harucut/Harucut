package com.harucut.event.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 매일 04:00 — 03:00 미참조 자산 정리 다음. 멱등이라 분산 락을 두지 않는다
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetentionScheduler {

    private final EventRetentionService retentionService;

    @Scheduled(cron = "${event.retention.cron:0 0 4 * * *}")
    public void run() {
        try {
            EventRetentionService.Result result = retentionService.run();
            log.info("[행사 보관] 결과 정리 {}건, 촬영 입력 행 {}건, 보관 종료 행사 {}건, 부가 행 정리 행사 {}건",
                    result.purgedResults(), result.deletedCaptureInputs(), result.archivedEvents(), result.cleanedEvents());
        } catch (Exception e) {
            log.error("[행사 보관] 실패 — 다음 실행이 이어서 본다", e);
        }
    }
}
