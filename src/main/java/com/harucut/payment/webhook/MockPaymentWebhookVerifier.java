package com.harucut.payment.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "payment.webhook.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentWebhookVerifier implements PaymentWebhookVerifier {

    @Override
    public boolean verify(String rawBody, Map<String, String> headers) {
        return true;
    }

    @Override
    public String provider() {
        return "mock";
    }
}
