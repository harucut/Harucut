package com.harucut.media.compose;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.harucut.media.service.ComposeService;
import com.harucut.storage.config.AwsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 이 소비자가 PENDING -> DONE/FAILED 를 쓰는 유일한 코드인데 테스트가 하나도 없었다.
// 실제 수명주기(start/stop)와 실제 롱폴링 루프를 태운다 — SqsClient 만 목이다.
//
// 스프링이 만든 ObjectMapper 를 쓰는 것이 중요하다. 이 매퍼가 "모르는 필드를 무시"하기
// 때문에 통지의 version·timestamp·responseContext 를 record 에 안 적어도 파싱된다.
// 직접 만든 매퍼로 바꾸면 조용히 깨진다
@JsonTest
@ActiveProfiles("test")
@DisplayName("ComposeResultConsumer")
class ComposeResultConsumerTest {

    private static final String QUEUE_URL = "https://sqs.test/queue";
    private static final Long JOB_ID = 231L;
    private static final String RESULT_KEY = "uploads/users/abc/fourcuts/job-231.png";
    private static final String THUMB_KEY = "uploads/users/abc/fourcuts/job-231-thumb.jpg";

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ComposeService composeService;

    private final ComposeOutputVerifier outputVerifier = mock(ComposeOutputVerifier.class);

    private ComposeResultConsumer consumer;
    private SqsClient sqsClient;

    @AfterEach
    void tearDown() {
        if (consumer != null && consumer.isRunning()) {
            consumer.stop();
        }
    }

    // ── 기동 관문 ──────────────────────────────

    @Test
    @DisplayName("큐 URL 이 비어 있으면 기동에서 죽는다 — 통지를 조용히 흘리는 것보다 낫다")
    void failsFastWithoutQueueUrl() {
        assertThatThrownBy(() -> new ComposeResultConsumer(
                mock(SqsClient.class), objectMapper, composeService, metrics(), mock(ComposeOutputVerifier.class), properties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compose-result-queue-url");
    }

    // ── 통지 처리 ──────────────────────────────

    @Nested
    @DisplayName("condition 분기")
    class Conditions {

        @Test
        @DisplayName("Success 는 Job 을 완료로 확정하고 메시지를 지운다")
        void successCompletesJobAndDeletesMessage() throws Exception {
            CountDownLatch deleted = runWith(notification("Success", """
                    "responsePayload": { "ok": true }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().completeJob(JOB_ID, RESULT_KEY, THUMB_KEY);
        }

        @Test
        @DisplayName("RetriesExhausted 는 영구 실패로 확정한다 — 함수가 최초 1회 + 재시도 2회를 다 썼다")
        void retriesExhaustedFailsJob() throws Exception {
            CountDownLatch deleted = runWith(notification("RetriesExhausted", """
                    "responsePayload": {
                      "errorMessage": "The specified key does not exist.",
                      "errorType": "software.amazon.awssdk.services.s3.model.NoSuchKeyException"
                    }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().failJob(eq(JOB_ID), anyString(), any(), isNull());
            then(composeService).should(never()).completeJob(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("실패 종류: 실행기가 문구 머리말로 실은 코드를 그대로 남긴다 — 코드 없는 옛 실행기의 실패는 UNKNOWN(다른 종류로 꾸미지 않는다)")
        void failureCodeFromNotification() throws Exception {
            CountDownLatch deleted = runWith(notification("RetriesExhausted", """
                    "responsePayload": {
                      "errorMessage": "[SOURCE_UNREADABLE] 이미지 형식을 인식할 수 없다",
                      "errorType": "com.harucut.media.compose.ComposeFailureException"
                    }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().failJob(eq(JOB_ID), anyString(), eq(ComposeFailureCode.SOURCE_UNREADABLE), isNull());
        }

        @Test
        @DisplayName("출력 계약 2 성공: 저장된 한 쌍을 대조해 같을 때만 체크섬과 함께 완료로 확정한다")
        void verifiedPairCompletes() throws Exception {
            given(outputVerifier.verified(any(), any())).willReturn(true);
            CountDownLatch deleted = runWith(verifiedNotification("Success", 2, """
                    "responsePayload": { "ok": true, "resultSha256": "UkVT", "thumbnailSha256": "VEhVTUI=", "attemptNo": 2, "contract": 2 }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().completeJob(JOB_ID, RESULT_KEY, THUMB_KEY, "UkVT", "VEhVTUI=");
            ArgumentCaptor<ComposeLambdaResult> result = ArgumentCaptor.captor();
            then(outputVerifier).should().verified(any(), result.capture());
            assertThat(result.getValue().resultSha256()).isEqualTo("UkVT");
        }

        @Test
        @DisplayName("출력 계약 2 성공인데 저장된 쌍이 다르거나 검증할 수 없으면 공개하지 않고 OUTPUT_CONFLICT 최종 실패")
        void unverifiedPairFails() throws Exception {
            given(outputVerifier.verified(any(), any())).willReturn(false);
            CountDownLatch deleted = runWith(verifiedNotification("Success", 1, """
                    "responsePayload": { "ok": true }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().failJob(eq(JOB_ID), anyString(), eq(ComposeFailureCode.OUTPUT_CONFLICT));
            then(composeService).should(never()).completeJob(anyLong(), anyString(), anyString(), any(), any());
            then(composeService).should(never()).completeJob(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("실패 통지는 payload 의 시도 번호와 함께 넘긴다 — 지난 시도의 늦은 실패는 서비스가 반영하지 않는다")
        void failurePassesAttempt() throws Exception {
            CountDownLatch deleted = runWith(verifiedNotification("RetriesExhausted", 1, """
                    "responsePayload": { "errorMessage": "[RENDER_FAILED] x", "errorType": "E" }"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).should().failJob(eq(JOB_ID), anyString(), eq(ComposeFailureCode.RENDER_FAILED), eq(1));
        }

        // 이 분기가 개선 전 30% 유실의 재발 방지선이다.
        // 여기서 failJob 을 부르면 재시도 가능한 실패가 영구 손실이 된다
        @Test
        @DisplayName("EventAgeExceeded 는 아무것도 확정하지 않는다 — PENDING 으로 두고 재실행에 맡긴다")
        void transientConditionLeavesJobPending() throws Exception {
            CountDownLatch deleted = runWith(notification("EventAgeExceeded", """
                    "responseContext": null"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("AWS 가 나중에 추가할 모르는 condition 도 PENDING 으로 떨어진다")
        void unknownConditionLeavesJobPending() throws Exception {
            CountDownLatch deleted = runWith(notification("SomethingAwsAddsIn2027", """
                    "responseContext": null"""));

            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            then(composeService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("실패 처리")
    class Failures {

        @Test
        @DisplayName("처리에 실패하면 메시지를 지우지 않는다 — 재전달되고 결국 DLQ 로 간다")
        void keepsMessageWhenHandlingFails() throws Exception {
            willThrow(new IllegalStateException("합성 Job이 사라졌다"))
                    .given(composeService).completeJob(anyLong(), anyString(), anyString());
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicBoolean deleteCalled = new AtomicBoolean(false);

            sqsClient = mock(SqsClient.class);
            given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                    .willAnswer(invocation -> {
                        if (attempted.getCount() > 0) {
                            attempted.countDown();
                            return response(notification("Success", """
                                    "responsePayload": { "ok": true }"""));
                        }
                        Thread.sleep(20);
                        return ReceiveMessageResponse.builder().messages(List.of()).build();
                    });
            given(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                    .willAnswer(invocation -> {
                        deleteCalled.set(true);
                        return null;
                    });

            start();
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);   // 지웠다면 이 사이에 지웠다

            assertThat(deleteCalled)
                    .as("지워버리면 통지가 사라지고 Job 은 PENDING 으로 남아 재실행에 떠넘겨진다")
                    .isFalse();
        }

        @Test
        @DisplayName("수신이 계속 실패해도 소비자 스레드가 죽지 않는다 — 백오프 후 다시 시도한다")
        void survivesReceiveFailure() throws Exception {
            CountDownLatch attempts = new CountDownLatch(2);
            sqsClient = mock(SqsClient.class);
            given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                    .willAnswer(invocation -> {
                        attempts.countDown();
                        throw new RuntimeException("자격증명 만료");
                    });

            start();

            // 백오프가 5초라 두 번째 시도까지 기다린다. 스레드가 죽었다면 영원히 1로 남는다
            assertThat(attempts.await(8, TimeUnit.SECONDS))
                    .as("여기서 안 잡으면 스레드가 죽고 소비가 영영 멈춘다")
                    .isTrue();
            assertThat(consumer.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("롱폴링 파라미터")
    class PollingParameters {

        @Test
        @DisplayName("대기·가시성 타임아웃을 요청에 명시한다 — 큐 속성에 의존하지 않는다")
        void specifiesWaitAndVisibilityExplicitly() throws Exception {
            CountDownLatch received = new CountDownLatch(1);
            sqsClient = mock(SqsClient.class);
            given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                    .willAnswer(invocation -> {
                        received.countDown();
                        Thread.sleep(20);
                        return ReceiveMessageResponse.builder().messages(List.of()).build();
                    });

            start();
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();

            org.mockito.ArgumentCaptor<ReceiveMessageRequest> captor =
                    org.mockito.ArgumentCaptor.captor();
            then(sqsClient).should(org.mockito.Mockito.atLeastOnce())
                    .receiveMessage(captor.capture());
            ReceiveMessageRequest request = captor.getValue();

            assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
            // 20 은 SQS 롱폴링 상한이자 SqsClient 소켓 타임아웃(30초)보다 짧아야 하는 값이다
            assertThat(request.waitTimeSeconds()).isEqualTo(20);
            assertThat(request.visibilityTimeout()).isEqualTo(30);
            assertThat(request.maxNumberOfMessages()).isEqualTo(10);
        }
    }

    // ── fixtures ──────────────────────────────

    // 통지 하나를 흘려보내고, 그 메시지가 삭제되면 열리는 래치를 돌려준다.
    // 삭제 = "이 통지는 처리가 끝났다"는 소비자의 유일한 신호다
    private CountDownLatch runWith(String body) {
        CountDownLatch deleted = new CountDownLatch(1);
        AtomicBoolean sent = new AtomicBoolean(false);

        sqsClient = mock(SqsClient.class);
        given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .willAnswer(invocation -> {
                    if (sent.compareAndSet(false, true)) {
                        return response(body);
                    }
                    Thread.sleep(20);
                    return ReceiveMessageResponse.builder().messages(List.of()).build();
                });
        given(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .willAnswer(invocation -> {
                    deleted.countDown();
                    return null;
                });

        start();
        return deleted;
    }

    private void start() {
        consumer = new ComposeResultConsumer(sqsClient, objectMapper, composeService,
                metrics(), outputVerifier, properties(QUEUE_URL));
        consumer.start();
    }

    private static ReceiveMessageResponse response(String body) {
        return ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .messageId("msg-1").receiptHandle("receipt-1").body(body).build())
                .build();
    }

    private static ComposeMetrics metrics() {
        return new ComposeMetrics(new SimpleMeterRegistry());
    }

    private static AwsProperties properties(String queueUrl) {
        return new AwsProperties("ap-northeast-2",
                new AwsProperties.S3("harucuts3"),
                new AwsProperties.Lambda("harucut-compose"),
                new AwsProperties.Sqs(queueUrl));
    }

    // 실제 Destination 통지 모양. 우리가 안 쓰는 필드를 일부러 남겨둔다
    private static String notification(String condition, String tail) {
        return """
                {
                  "version": "1.0",
                  "timestamp": "2026-08-21T07:11:02.082Z",
                  "requestContext": {
                    "requestId": "b57694bf-8ef0-42e4-aac7-c6997d00c6c2",
                    "functionArn": "arn:aws:lambda:ap-northeast-2:123:function:harucut-compose",
                    "condition": "%s",
                    "approximateInvokeCount": 1
                  },
                  "requestPayload": {
                    "bucket": "harucuts3",
                    "jobId": 231,
                    "spec": {
                      "canvasWidth": 400,
                      "canvasHeight": 1200,
                      "background": { "type": "COLOR", "value": "#FFFFFF" },
                      "slots": [
                        { "x": 0, "y": 0,   "width": 400, "height": 300 },
                        { "x": 0, "y": 300, "width": 400, "height": 300 },
                        { "x": 0, "y": 600, "width": 400, "height": 300 },
                        { "x": 0, "y": 900, "width": 400, "height": 300 }
                      ],
                      "cellCutouts": [false, false, false, false],
                      "layers": []
                    },
                    "sourceKeys": [
                      "uploads/users/abc/1.jpg", "uploads/users/abc/2.jpg",
                      "uploads/users/abc/3.jpg", "uploads/users/abc/4.jpg"
                    ],
                    "resultKey": "%s",
                    "thumbnailKey": "%s"
                  },
                  %s
                }
                """.formatted(condition, RESULT_KEY, THUMB_KEY, tail);
    }

    // 출력 계약 2 payload — 시도 번호·계약이 실린다
    private static String verifiedNotification(String condition, int attemptNo, String tail) {
        return notification(condition, tail).replace("\"thumbnailKey\": \"" + THUMB_KEY + "\"",
                "\"thumbnailKey\": \"" + THUMB_KEY + "\", \"attemptNo\": " + attemptNo + ", \"contract\": 2");
    }
}
