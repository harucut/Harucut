package com.harucut.payment.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 결제를 닫아 둔 운영 — 어떤 웹훅도 받지 않는다(PAY-008). 공개 경로라 mock 처럼 통과시키면 누구나 수신 행을 쌓을 수 있다 */
@Component
@ConditionalOnProperty(name = "payment.webhook.provider", havingValue = "disabled")
public class DisabledPaymentWebhookVerifier implements PaymentWebhookVerifier {

    @Override
    public boolean verify(String rawBody, Map<String, String> headers) {
        return false;
    }

    @Override
    public String provider() {
        return "disabled";
    }
}
