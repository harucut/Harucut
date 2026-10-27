package com.harucut.media.compose;

import com.harucut.media.service.ComposeService;
import com.harucut.storage.config.AwsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Lambda Destination 통지를 받아 Job을 DONE/FAILED로 확정한다.
//
// @Scheduled가 아니라 전용 스레드인 이유: 롱폴링이 스레드를 20초씩 붙잡는다.
// 스케줄링 풀(4개)에 넣으면 배치들과 자리를 다투고, 그 결합이 설정 파일에 숨는다.
//
// SmartLifecycle의 기본 phase(Integer.MAX_VALUE)를 그대로 쓴다 — 스프링은 높은 phase부터
// 멈추므로 소비자가 가장 먼저 멈추고, DB·커넥션 풀은 그 뒤에 내려간다. 순서가 반대면
// 처리 중이던 통지가 죽은 커넥션을 잡는다

@Slf4j
@Component
@ConditionalOnProperty(name = "compose.result-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ComposeResultConsumer implements SmartLifecycle {

    // 롱폴링 상한값(20)이다. 더 짧게 잡을 이유가 없다 — 메시지가 도착하면 즉시 반환하므로
    // 이 값은 통지 지연에 영향이 없고, 큐가 비어 있을 때의 유휴 호출 수만 정한다.
    // 20초면 인스턴스당 월 13만 회라 무료 티어(100만) 안에서 7대까지 늘릴 수 있다.
    // 거짓 빈 응답(메시지가 있는데 빈 응답이 오는 것)도 값이 낮을수록 잦아진다.
    // 대가는 종료 대기가 최악 20초로 느는 것뿐이고 stop_grace_period(60초) 안이다.
    //
    // ⚠️ 큐 속성 ReceiveMessageWaitTimeSeconds는 이 값에 덮인다 — 요청 파라미터가 이긴다.
    //    콘솔에서 바꿔도 아무 일이 안 일어나므로 값은 여기서만 고친다.
    // ⚠️ SqsClient의 소켓 타임아웃(기본 30초)보다 짧아야 한다.
    //    넘기면 모든 폴링이 타임아웃 예외로 끝나면서 "가끔 느린 것"처럼 보인다
    private static final int WAIT_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;

    // 큐 기본값과 같은 30초다. 그래도 명시한다 — 안 주면 큐 속성을 따르게 되고,
    // 그 값은 콘솔에서 누가 바꿔도 코드 리뷰에 안 잡힌다 (WAIT_SECONDS와 같은 이유).
    //
    // 처리 시간의 상한이기도 하다. handle()이 30초를 넘기면 같은 통지가 다시 배달되고
    // 두 스레드가 같은 Job을 건드린다 — completeJob의 status != PENDING 조기 반환이
    // 그걸 흡수하지만, 그건 방어선이지 설계가 아니다. 통지 처리는 DB 트랜잭션 하나라
    // 30초는 매우 넉넉하다. 처리에 외부 호출이 붙는 날 이 값을 다시 봐야 한다
    private static final int VISIBILITY_TIMEOUT_SECONDS = 30;
    private static final long ERROR_BACKOFF_MILLIS = 5_000;

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final ComposeService composeService;
    private final ComposeMetrics metrics;
    private final String queueUrl;

    private volatile boolean running;
    private ExecutorService worker;

    public ComposeResultConsumer(SqsClient sqsClient, ObjectMapper objectMapper,
                                 ComposeService composeService, ComposeMetrics metrics,
                                 AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.composeService = composeService;
        this.metrics = metrics;
        if (awsProperties.sqs() == null || awsProperties.sqs().composeResultQueueUrl() == null
                || awsProperties.sqs().composeResultQueueUrl().isBlank()) {
            // 소비자를 켰는데 큐가 없으면 기동에서 죽는다 — 통지를 조용히 흘리는 것보다 낫다
            throw new IllegalStateException(
                    "합성 결과 소비자를 켰는데 cloud.aws.sqs.compose-result-queue-url이 비어 있다");
        }
        this.queueUrl = awsProperties.sqs().composeResultQueueUrl();
    }

    // ── 수명주기 ──────────────────────────────

    @Override
    public void start() {
        running = true;
        worker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "compose-result"));
        worker.submit(this::loop);
        log.info("[합성 통지] 소비자 시작: queue={}", queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        // shutdownNow로 인터럽트를 건다. SDK의 블로킹 호출이 즉시 안 깨질 수 있지만
        // 롱폴링이 20초라 최악도 그 안이다
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(WAIT_SECONDS + 5, TimeUnit.SECONDS)) {
                log.warn("[합성 통지] 소비자가 제때 멈추지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
        log.info("[합성 통지] 소비자 정지");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ── 수신 ──────────────────────────────

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                // 여기서 안 잡으면 스레드가 죽고 소비가 영영 멈춘다.
                // 자격증명 만료·네트워크 단절처럼 계속 실패하는 경우 뜨거운 루프가 되므로 쉬어 간다
                log.error("[합성 통지] 수신 실패 — {}ms 뒤 재시도", ERROR_BACKOFF_MILLIS, e);
                if (!sleepQuietly()) {
                    return;
                }
            }
        }
    }

    private void pollOnce() {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES)
                .waitTimeSeconds(WAIT_SECONDS)
                .visibilityTimeout(VISIBILITY_TIMEOUT_SECONDS)
                .build()).messages();

        for (Message message : messages) {
            if (!running) {
                // 종료 중 — 안 지운 메시지는 가시성 타임아웃 뒤 다음 인스턴스에 다시 온다
                return;
            }
            handleQuietly(message);
        }
    }

    private void handleQuietly(Message message) {
        try {
            handle(objectMapper.readValue(message.body(), ComposeResultNotification.class));
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (Exception e) {
            // 일부러 지우지 않는다 — 가시성 타임아웃 뒤 재전달되고, 5회 실패하면 DLQ로 간다.
            // 지워버리면 통지가 사라지고 Job은 PENDING으로 남아 재실행 스케줄러에 떠넘겨진다
            log.error("[합성 통지] 처리 실패 messageId={}", message.messageId(), e);
        }
    }

    private void handle(ComposeResultNotification notification) {
        Long jobId = notification.jobId();
        if (jobId == null) {
            // jobId 없는 payload = 이 전환 이전 서버가 보낸 것. 그쪽은 동기 호출이라
            // 자기가 결과를 기록했다 — 여기서 할 일이 없다
            metrics.notifiedWithoutJobId();
            log.warn("[합성 통지] jobId 없는 통지 — 건너뛴다: condition={}", notification.condition());
            return;
        }

        String condition = notification.condition();
        if ("Success".equals(condition)) {
            ComposeLambdaPayload payload = notification.requestPayload();
            composeService.completeJob(jobId, payload.resultKey(), payload.thumbnailKey());
            metrics.notifiedDone();
            log.info("[합성 통지] 완료: jobId={}", jobId);
            return;
        }

        if ("RetriesExhausted".equals(condition)) {
            // 함수가 최초 1회 + 재시도 2회를 모두 예외로 끝냈다 — 영구 실패로 본다
            composeService.failJob(jobId, notification.failureReason(), notification.failureCode());
            metrics.notifiedFailed();
            log.warn("[합성 통지] 영구 실패: jobId={} reason={}", jobId, notification.failureReason());
            return;
        }

        // EventAgeExceeded · ZeroReservedConcurrency, 그리고 앞으로 AWS가 추가할 값들.
        // 전부 "일시적"으로 본다 — PENDING 그대로 두면 ComposeRerunScheduler가 다시 던진다.
        // 여기서 failJob을 부르면 재시도 가능한 실패가 영구 손실이 된다
        // (2026-08-21 측정에서 429가 정확히 그렇게 죽었다.
        //  docs/adr-0001-compose-result-channel.md 참고)
        metrics.notifiedTransient();
        log.warn("[합성 통지] 일시적 실패로 본다 — PENDING 유지: jobId={} condition={}", jobId, condition);
    }

    private boolean sleepQuietly() {
        try {
            Thread.sleep(ERROR_BACKOFF_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
