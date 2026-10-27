package com.harucut.push.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

// 하루 한 번(기본 10:00 KST). 여러 인스턴스가 같이 돌아도 push_dedup 이 한 번만 보내게 한다 — 분산 잠금을 두지 않는다
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiringNoticeScheduler {

    private final SubscriptionExpiringNoticeService noticeService;
    private final Clock clock;

    @Scheduled(cron = "${push.expiring.cron:0 0 10 * * *}", zone = "Asia/Seoul")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        int sent = 0;
        for (Long id : noticeService.candidates(today)) {
            try {
                if (noticeService.notify(id, today)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("[만료 예고] subscriptionId={} 처리 실패 — 건너뛰고 계속", id, e);
            }
        }
        log.info("[만료 예고] {} 기준 {}건 요청", today, sent);
    }
}
