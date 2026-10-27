package com.harucut.media.compose;

/**
 * Lambda 성공 응답 — Destination 통지의 responsePayload 로 서버에 돌아온다. 예전 계약은 {"ok":true} 뿐이라 나머지가 null 이다.
 * 체크섬은 S3 checksumSHA256 과 같은 표기(바이트의 SHA-256 을 base64)다.
 */
public record ComposeLambdaResult(
        Boolean ok,
        String resultSha256,
        String thumbnailSha256,
        Integer attemptNo,
        Integer contract
) {
    public static ComposeLambdaResult legacy() {
        return new ComposeLambdaResult(true, null, null, null, null);
    }
}
