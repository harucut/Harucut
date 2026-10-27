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

    // 실패면 errorType·errorMessage, 성공이면 Lambda 응답 — 예전 계약은 {"ok":true}, 계약 2 는 두 체크섬·시도 번호(ComposeLambdaResult)
    public record ResponsePayload(String errorType, String errorMessage,
                                  String resultSha256, String thumbnailSha256, Integer attemptNo) {

        public ResponsePayload(String errorType, String errorMessage) {
            this(errorType, errorMessage, null, null, null);
        }
    }

    /** 성공 응답으로 읽은 값 — 계약 2 의 체크섬 쌍 */
    public ComposeLambdaResult result() {
        return responsePayload == null ? null : new ComposeLambdaResult(true, responsePayload.resultSha256(),
                responsePayload.thumbnailSha256(), responsePayload.attemptNo(),
                requestPayload == null ? null : requestPayload.contract());
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
