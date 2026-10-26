package com.harucut.payment.config;

import com.harucut.payment.gateway.PaymentGateway;
import com.harucut.payment.gateway.PgProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!local & !test")
public class MockGatewayGuard {

    public MockGatewayGuard(PaymentGateway paymentGateway, PaymentProperties properties) {
        boolean mockGateway = paymentGateway.provider() == PgProvider.MOCK;
        // mock 웹훅 검증기는 서명을 보지 않고 전부 통과시킨다
        boolean mockWebhook = "mock".equals(properties.webhook().provider());
        if (!mockGateway && !mockWebhook) {
            return;
        }
        if (!properties.mock().allowedOutsideLocal()) {
            throw new IllegalStateException("Mock payment gateway or webhook verifier must not be active outside local/test profiles.");
        }
        log.warn("[결제] mock(게이트웨이={}, 웹훅 검증={})이 허용된 채로 기동했다 — 실제 결제·서명 검증은 일어나지 않는다. "
                + "PG·웹훅을 붙이면 payment.mock.allowed-outside-local(PAYMENT_MOCK_ALLOWED)을 지울 것", mockGateway, mockWebhook);
    }
}
