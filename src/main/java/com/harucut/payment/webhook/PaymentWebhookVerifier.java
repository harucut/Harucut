package com.harucut.payment.webhook;

import java.util.Map;

/** 웹훅 서명 검증. headers 는 소문자 이름 → 값. 원문 body 바이트 그대로 검증한다(JSON 재직렬화 금지) */
public interface PaymentWebhookVerifier {

    boolean verify(String rawBody, Map<String, String> headers);

    /** 제공자 이름 — 수신 기록의 범위 */
    String provider();
}
