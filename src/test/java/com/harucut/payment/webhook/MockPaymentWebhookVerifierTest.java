package com.harucut.payment.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MockPaymentWebhookVerifier")
class MockPaymentWebhookVerifierTest {

    private final MockPaymentWebhookVerifier verifier = new MockPaymentWebhookVerifier();

    @Test
    @DisplayName("서명이 무엇이든 통과한다")
    void verifiesAnySignature() {
        assertThat(verifier.verify("{}", Map.of("webhook-signature", "v1,anything"))).isTrue();
    }

    @Test
    @DisplayName("서명이 없어도 통과한다")
    void verifiesMissingSignature() {
        assertThat(verifier.verify("{}", Map.of())).isTrue();
    }
}
