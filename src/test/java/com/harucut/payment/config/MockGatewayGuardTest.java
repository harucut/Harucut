package com.harucut.payment.config;

import com.harucut.payment.gateway.PaymentGateway;
import com.harucut.payment.gateway.PgProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/*
 * @Profile("!local & !test") 조건 자체는 단위 테스트로 못 잡는다.
 * 여기서는 생성자의 판정만 검증한다 — 어느 프로파일에서 이 가드가 뜨는지는 설정의 몫.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockGatewayGuard")
class MockGatewayGuardTest {

    @Mock
    private PaymentGateway paymentGateway;

    @Test
    @DisplayName("mock 게이트웨이가 잡혀 있으면 기동을 실패시킨다")
    void rejectsMockGateway() {
        given(paymentGateway.provider()).willReturn(PgProvider.MOCK);

        assertThatThrownBy(() -> new MockGatewayGuard(paymentGateway, properties(false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("mock이어도 명시적으로 허용했으면 통과한다")
    void allowsMockGatewayWhenOptedIn() {
        given(paymentGateway.provider()).willReturn(PgProvider.MOCK);

        assertThatCode(() -> new MockGatewayGuard(paymentGateway, properties(true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("게이트웨이·웹훅 검증기 모두 mock 이 아니면 통과한다")
    void allowsRealGateway() {
        given(paymentGateway.provider()).willReturn(PgProvider.TOSS);

        assertThatCode(() -> new MockGatewayGuard(paymentGateway, properties(false, "portone")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("게이트웨이가 실제여도 웹훅 검증기가 mock 이면(서명 무검증) 허용 없이는 기동을 실패시킨다")
    void rejectsMockWebhookVerifier() {
        given(paymentGateway.provider()).willReturn(PgProvider.TOSS);

        assertThatThrownBy(() -> new MockGatewayGuard(paymentGateway, properties(false, "mock")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new MockGatewayGuard(paymentGateway, properties(true, "mock")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("허용 설정은 아무것도 안 주면 꺼져 있다")
    void optInDefaultsToFalse() {
        // 이 기본값이 true 가 되면 가드가 조용히 꺼진다. 설정을 하나도 안 준 상태로 바인딩해 확인한다
        PaymentProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("payment", PaymentProperties.class);

        assertThat(bound.mock().allowedOutsideLocal()).isFalse();
    }

    private PaymentProperties properties(boolean allowedOutsideLocal) {
        return properties(allowedOutsideLocal, "mock");
    }

    private PaymentProperties properties(boolean allowedOutsideLocal, String webhookProvider) {
        return new PaymentProperties(
                new PaymentProperties.Gateway("mock"),
                new PaymentProperties.Mock(false, allowedOutsideLocal),
                3,
                new PaymentProperties.Webhook(webhookProvider, 300),
                new PaymentProperties.PortOne(null, null, null, "https://api.portone.io")
        );
    }
}
