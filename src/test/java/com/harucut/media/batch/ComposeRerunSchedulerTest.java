package com.harucut.media.batch;

import com.harucut.media.compose.ComposeMetrics;
import com.harucut.media.compose.ComposeRequestedEvent;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.compose.ComposeWorker;
import com.harucut.media.compose.ImageFormat;
import com.harucut.media.service.ComposeService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 재실행 배치는 "통지가 끝내 오지 않은 Job"의 마지막 방어선이다.
// 여기가 멈추면 유실이 조용히 쌓이므로, 한 건이 터져도 나머지가 계속 도는지가 핵심이다
@ExtendWith(MockitoExtension.class)
@DisplayName("ComposeRerunScheduler")
class ComposeRerunSchedulerTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 20;

    @Mock
    private ComposeService composeService;

    @Mock
    private ComposeWorker composeWorker;

    private ComposeRerunScheduler scheduler;
    private ComposeMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ComposeMetrics(new SimpleMeterRegistry());
        scheduler = new ComposeRerunScheduler(composeService, composeWorker, metrics, STALE_AFTER, BATCH_SIZE);
    }

    @Test
    @DisplayName("멈춰 있는 Job 을 전부 워커로 다시 던진다")
    void resubmitsStalledJobs() {
        given(composeService.findStalled(STALE_AFTER, BATCH_SIZE))
                .willReturn(List.of(event(1L), event(2L), event(3L)));

        scheduler.run();

        then(composeWorker).should().rerun(event(1L));
        then(composeWorker).should().rerun(event(2L));
        then(composeWorker).should().rerun(event(3L));
    }

    @Test
    @DisplayName("대상이 없으면 워커를 건드리지 않는다 — 빈 주기가 대부분이다")
    void doesNothingWhenNothingStalled() {
        given(composeService.findStalled(STALE_AFTER, BATCH_SIZE)).willReturn(List.of());

        scheduler.run();

        then(composeWorker).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("설정한 staleAfter·batchSize 를 그대로 조회에 넘긴다")
    void passesConfiguredWindowAndLimit() {
        Duration customWindow = Duration.ofMinutes(3);
        given(composeService.findStalled(customWindow, 5)).willReturn(List.of());

        new ComposeRerunScheduler(composeService, composeWorker, metrics, customWindow, 5).run();

        then(composeService).should().findStalled(customWindow, 5);
    }

    // 한 건이 스케줄러 스레드로 예외를 올리면 fixedDelay 주기 자체가 그 뒤로 안 돈다.
    // 워커가 안에서 삼키는 것이 설계지만, 그 계약이 깨지면 재실행이 통째로 멈춘다
    @Test
    @DisplayName("한 건이 터지면 그 주기는 멈춘다 — 워커가 삼켜야 한다는 계약을 드러낸다")
    void propagatesWorkerFailure() {
        given(composeService.findStalled(STALE_AFTER, BATCH_SIZE))
                .willReturn(List.of(event(1L), event(2L)));
        willThrow(new RuntimeException("접수 실패")).given(composeWorker).rerun(event(1L));

        try {
            scheduler.run();
        } catch (RuntimeException expected) {
            // 계약 위반 시의 실제 동작을 고정한다
        }

        then(composeWorker).should(never()).rerun(event(2L));
    }

    @Test
    @DisplayName("조회가 실패하면 워커를 부르지 않는다")
    void skipsWorkerWhenQueryFails() {
        given(composeService.findStalled(any(), anyInt()))
                .willThrow(new RuntimeException("DB 연결 끊김"));

        try {
            scheduler.run();
        } catch (RuntimeException expected) {
            // 다음 주기에 다시 시도된다
        }

        then(composeWorker).shouldHaveNoInteractions();
    }

    // ── fixtures ──────────────────────────────

    private static ComposeRequestedEvent event(Long jobId) {
        return new ComposeRequestedEvent(jobId, spec(),
                List.of("uploads/users/abc/fourcuts/sources/1.png",
                        "uploads/users/abc/fourcuts/sources/2.png",
                        "uploads/users/abc/fourcuts/sources/3.png",
                        "uploads/users/abc/fourcuts/sources/4.png"),
                "uploads/users/abc/fourcuts/job-" + jobId + ".png",
                "uploads/users/abc/fourcuts/job-" + jobId + "-thumb.jpg",
                ImageFormat.PNG);
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000,
                new BackgroundAttributes.Color("#FFE4E1"),
                FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false),
                List.of());
    }

    @Test
    @DisplayName("재접수 예산을 다 쓴 작업은 DISPATCH_EXHAUSTED 로 끝낸다 — 시도 번호를 대조하고, 한 건이 터져도 나머지와 재접수는 계속 간다")
    void failsExpiredJobs() {
        given(composeService.findDispatchExpired(STALE_AFTER, BATCH_SIZE)).willReturn(List.of(
                new ComposeService.ExpiredDispatch(1L, 4), new ComposeService.ExpiredDispatch(2L, 0)));
        given(composeService.failJob(1L, "앱 재접수 상한 초과(횟수·총 기한)", com.harucut.media.compose.ComposeFailureCode.DISPATCH_EXHAUSTED, 4))
                .willThrow(new RuntimeException("DB"));
        given(composeService.failJob(2L, "앱 재접수 상한 초과(횟수·총 기한)", com.harucut.media.compose.ComposeFailureCode.DISPATCH_EXHAUSTED, 0))
                .willReturn(true);
        given(composeService.findStalled(STALE_AFTER, BATCH_SIZE)).willReturn(List.of(event(3L)));

        scheduler.run();

        then(composeService).should().failJob(2L, "앱 재접수 상한 초과(횟수·총 기한)", com.harucut.media.compose.ComposeFailureCode.DISPATCH_EXHAUSTED, 0);
        then(composeWorker).should().rerun(event(3L));
    }
}
