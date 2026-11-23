package com.harucut.payment.gateway;

import com.harucut.payment.config.PaymentProperties;
import com.harucut.payment.gateway.dto.BillingChargeCommand;
import com.harucut.payment.gateway.dto.BillingKeyResult;
import com.harucut.payment.gateway.dto.IssueBillingKeyCommand;
import com.harucut.payment.gateway.dto.PaymentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MockPaymentGateway")
class MockPaymentGatewayTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 18, 10, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final MockPaymentGateway gateway = gateway(false);

    @Test
    @DisplayName("provider는 MOCK이다")
    void providerIsMock() {
        assertThat(gateway.provider()).isEqualTo(PgProvider.MOCK);
    }

    @Nested
    @DisplayName("빌링키 발급")
    class IssueBillingKey {

        @Test
        @DisplayName("정상 발급 — 빌링키 값에 customerKey가 들어 있다")
        void issuedKeyContainsCustomerKey() {
            BillingKeyResult result = gateway.issueBillingKey(command("customer-1", "auth-1"));

            assertThat(result.success()).isTrue();
            assertThat(result.billingKeyValue()).contains("customer-1");
        }

        @Test
        @DisplayName("발급할 때마다 빌링키 값이 다르다")
        void issuedKeysAreUnique() {
            BillingKeyResult first = gateway.issueBillingKey(command("customer-1", "auth-1"));
            BillingKeyResult second = gateway.issueBillingKey(command("customer-1", "auth-1"));

            assertThat(first.billingKeyValue()).isNotEqualTo(second.billingKeyValue());
        }

        @Test
        @DisplayName("authKey에 FAIL이 들어 있으면 발급이 실패한다")
        void failsWhenAuthKeyContainsFailMarker() {
            BillingKeyResult result = gateway.issueBillingKey(command("customer-1", "auth-FAIL"));

            assertThat(result.success()).isFalse();
            assertThat(result.failureCode()).isEqualTo("MOCK_ISSUE_FAILED");
        }

        @Test
        @DisplayName("실패 결과에는 빌링키 값이 없다")
        void failureCarriesNoBillingKey() {
            BillingKeyResult result = gateway.issueBillingKey(command("customer-1", "auth-FAIL"));

            assertThat(result.billingKeyValue()).isNull();
        }

        private IssueBillingKeyCommand command(String customerKey, String authKey) {
            return new IssueBillingKeyCommand(customerKey, authKey);
        }
    }

    @Nested
    @DisplayName("청구")
    class Charge {

        @Test
        @DisplayName("정상 청구 — pgTransactionId는 mock-tx-{orderKey}다")
        void transactionIdComesFromOrderKey() {
            PaymentResult result = gateway.charge(command("mock-bk-customer-1-123"));

            assertThat(result.success()).isTrue();
            assertThat(result.pgTransactionId()).isEqualTo("mock-tx-order-1");
        }

        @Test
        @DisplayName("승인 시각은 고정 Clock의 now다")
        void approvedAtComesFromClock() {
            PaymentResult result = gateway.charge(command("mock-bk-customer-1-123"));

            assertThat(result.approvedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("빌링키 값에 DECLINE 마커가 있으면 청구가 실패한다")
        void failsWhenBillingKeyCarriesDeclineMarker() {
            PaymentResult result = gateway.charge(command("mock-bk-DECLINE-123"));

            assertThat(result.success()).isFalse();
            assertThat(result.failureCode()).isEqualTo("MOCK_CHARGE_FAILED");
        }

        @Test
        @DisplayName("fail-charge 설정이 켜져 있으면 정상 키여도 청구가 실패한다")
        void failsWhenFailChargeIsOn() {
            MockPaymentGateway failing = gateway(true);

            PaymentResult result = failing.charge(command("mock-bk-customer-1-123"));

            assertThat(result.success()).isFalse();
        }

        /*
         * authKey의 DECLINE이 발급된 빌링키 값에 실려 들어가
         * 발급은 성공하고 청구만 실패한다. 402 테스트가 이 연결에 기댄다.
         */
        @Test
        @DisplayName("authKey에 DECLINE을 넣으면 발급은 성공하고 그 키로 하는 청구가 실패한다")
        void declineMarkerPropagatesFromAuthKeyToCharge() {
            BillingKeyResult issued =
                    gateway.issueBillingKey(new IssueBillingKeyCommand("customer-1", "auth-DECLINE"));
            assertThat(issued.success()).isTrue();

            PaymentResult result = gateway.charge(command(issued.billingKeyValue()));

            assertThat(result.success()).isFalse();
        }

        private BillingChargeCommand command(String billingKeyValue) {
            return new BillingChargeCommand(billingKeyValue, "order-1", 3900, "PLUS 구독", "customer-1");
        }
    }

    private static MockPaymentGateway gateway(boolean failCharge) {
        PaymentProperties properties = new PaymentProperties(
                new PaymentProperties.Gateway("mock"),
                new PaymentProperties.Mock(failCharge, false),
                3,
                new PaymentProperties.Webhook("mock", 300),
                new PaymentProperties.PortOne(null, null, null, "https://api.portone.io")
        );
        Clock clock = Clock.fixed(FIXED_NOW.atZone(ZONE).toInstant(), ZONE);
        return new MockPaymentGateway(properties, clock);
    }
}
