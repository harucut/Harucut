package com.harucut.media.compose;

// Lambda Destination이 SQS로 보내는 통지. 필요한 필드만 적는다 —
// version·timestamp·requestId·responseContext·stackTrace는 우리가 안 쓴다.
// 이게 성립하는 건 스프링이 만들어준 ObjectMapper가 모르는 필드를 무시하기 때문이다.
// 직접 만든 매퍼(JsonMapper.builder().build())로 읽으면 깨질 수 있으므로 주입받아 쓴다
public record ComposeResultNotification(
        RequestContext requestContext,
        ComposeLambdaPayload requestPayload,
        ResponsePayload responsePayload
) {

    // condition을 enum으로 받지 않는다. AWS가 새 값을 추가해도 역직렬화가 깨지면 안 되고,
    // 모르는 값은 "건드리지 않는다"로 떨어져야 한다 (소비자의 마지막 분기)
    public record RequestContext(String condition) {
    }

    // 성공 통지는 {"ok":true}라 두 필드가 다 null이다. 실패일 때만 채워진다
    public record ResponsePayload(String errorType, String errorMessage) {
    }

    public String condition() {
        return requestContext == null ? null : requestContext.condition();
    }

    public Long jobId() {
        return requestPayload == null ? null : requestPayload.jobId();
    }

    // stackTrace는 일부러 빼고 조립한다 — failureReason은 255자에서 잘린다(ComposeJob.fail).
    // 스택을 넣으면 정작 원인 문구가 잘려 나간다
    /** 실패 종류 — 실행기가 문구 머리말로 실은 코드, 시간 초과, 그 밖은 UNKNOWN */
    public ComposeFailureCode failureCode() {
        return responsePayload == null ? ComposeFailureCode.UNKNOWN
                : ComposeFailureCode.from(responsePayload.errorType(), responsePayload.errorMessage());
    }

    public String failureReason() {
        if (responsePayload == null || responsePayload.errorMessage() == null) {
            return condition();
        }
        return responsePayload.errorType() + ": " + responsePayload.errorMessage();
    }
}
