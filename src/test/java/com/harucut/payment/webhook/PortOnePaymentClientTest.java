package com.harucut.payment.webhook;

import com.harucut.payment.config.PaymentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 PortOne 응답 모양은 실연동 미확인 — V2 문서의 필드(id·status·storeId·amount.total·currency)만 읽는다
@DisplayName("PortOnePaymentClient")
class PortOnePaymentClientTest {

    @Test
    @DisplayName("PortOne 인증 헤더와 설정한 storeId 로 결제를 조회하고 상태·상점·금액·통화를 읽는다")
    void fetchesPayment() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/pay-1?storeId=store-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("storeId", "store-1"))
                .andExpect(header("Authorization", "PortOne api-secret"))
                .andRespond(withSuccess("""
                        {"id":"pay-1","status":"PAID","storeId":"store-1","amount":{"total":1000000,"paid":1000000},"currency":"KRW"}
                        """, MediaType.APPLICATION_JSON));

        PortOnePaymentClient.PortOnePayment payment = new PortOnePaymentClient(properties("api-secret"), builder).get("pay-1");

        assertThat(payment).isEqualTo(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "store-1", 1_000_000L, "KRW"));
        server.verify();
    }

    @Test
    @DisplayName("결제 id 는 경로 조각으로 인코딩된다 — / 나 ? 가 경로·쿼리를 바꾸지 못한다")
    void encodesPaymentId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/a%2Fb%3Fc?storeId=store-1"))
                .andRespond(withSuccess("{\"id\":\"a/b?c\",\"status\":\"PAID\"}", MediaType.APPLICATION_JSON));

        assertThat(new PortOnePaymentClient(properties("api-secret"), builder).get("a/b?c").status()).isEqualTo("PAID");
        server.verify();
    }

    @Test
    @DisplayName("API 비밀이나 상점 id 가 없으면 기동하지 않는다")
    void requiresSecretAndStore() {
        assertThatThrownBy(() -> new PortOnePaymentClient(properties(" "), RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PortOnePaymentClient(properties("api-secret", null), RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaymentProperties properties(String apiSecret) {
        return properties(apiSecret, "store-1");
    }

    private static PaymentProperties properties(String apiSecret, String storeId) {
        return new PaymentProperties(new PaymentProperties.Gateway("mock"), new PaymentProperties.Mock(false, false), 3,
                new PaymentProperties.Webhook("portone", 300),
                new PaymentProperties.PortOne("whsec_x", apiSecret, storeId, "https://api.portone.io"));
    }
}
