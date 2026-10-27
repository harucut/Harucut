package com.harucut.media.compose;

import com.harucut.storage.config.AwsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import tools.jackson.databind.ObjectMapper;

// 실행기 — 그리지 않고 Lambda를 비동기(InvocationType.EVENT)로 호출한다.
// 여기서 하는 일은 "접수"까지다: 페이로드를 넘기고 202를 받으면 끝이고, 결과는 모른다.
// 완료/실패는 Lambda Destination이 SQS로 보낸 통지를 ComposeResultConsumer가 받아 기록한다.
//
// 동기 호출(REQUEST_RESPONSE)이 아닌 이유는 동시성 한도를 만났을 때의 행동이 다르기 때문이다 —
// 동기는 기다리지 않고 429로 깨지고, 비동기는 초과분이 Lambda 내부 큐에서 대기한다.
// 측정에서 동시 30건 중 9건(30%)이 정확히 그렇게 영구 손실됐다
// (docs/adr-0001-compose-result-channel.md, docs/measurement-2026-08-23.md).
//
// 접수 실패는 예외로 던진다. 워커가 그걸 삼켜 Job을 PENDING으로 두고,
// ComposeRerunScheduler가 다시 던진다 — FAILED로 확정하지 않는다
@Slf4j
@Component
public class LambdaComposeExecutor implements ComposeExecutor {

    private final LambdaClient lambdaClient;
    private final ObjectMapper objectMapper;
    private final String functionName;
    private final String bucket;

    public LambdaComposeExecutor(LambdaClient lambdaClient, ObjectMapper objectMapper,
                                 AwsProperties awsProperties) {
        this.lambdaClient = lambdaClient;
        this.objectMapper = objectMapper;
        this.bucket = awsProperties.s3().bucket();
        if (awsProperties.lambda() == null || awsProperties.lambda().composeFunction() == null
                || awsProperties.lambda().composeFunction().isBlank()) {
            // 함수 이름이 없으면 기동에서 죽는다 — 첫 합성 요청에서 죽는 것보다 낫다
            throw new IllegalStateException(
                    "cloud.aws.lambda.compose-function이 비어 있다 (환경변수 COMPOSE_LAMBDA_FUNCTION)");
        }
        this.functionName = awsProperties.lambda().composeFunction();
    }

    @Override
    public void execute(ComposeRequestedEvent event) {
        // 포맷을 여기서 정하지 않고 이벤트에 실려 온 값을 그대로 넘긴다 —
        // resultKey를 만든 곳(ComposeService.RESULT_FORMAT)과 같은 값이어야 하는데,
        // 실행기가 자기 판단으로 고르면 그 보장이 사라진다
        String payload = objectMapper.writeValueAsString(new ComposeLambdaPayload(
                bucket, event.jobId(), event.spec(), event.sourceKeys(),
                event.resultKey(), event.thumbnailKey(), event.outputFormat(), event.attemptNo(), event.contract()));

        InvokeResponse response = lambdaClient.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(payload))
                .build());

        // 비동기 접수는 202다. 200을 기대하면 성공한 호출이 전부 예외가 된다
        if (response.statusCode() != 202) {
            throw new IllegalStateException("Lambda 접수 실패 (status=" + response.statusCode()
                    + "): " + excerpt(response.payload()));
        }
    }

    private String excerpt(SdkBytes payload) {
        if (payload == null) {
            return "";
        }
        String text = payload.asUtf8String();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
