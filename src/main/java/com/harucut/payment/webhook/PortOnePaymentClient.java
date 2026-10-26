package com.harucut.payment.webhook;

import com.harucut.payment.config.PaymentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * PortOne V2 결제 단건 조회 — 웹훅 본문을 믿지 않고 서버가 직접 금액·상태를 확인한다(R5 12절). <b>실연동 미확인</b>.
 * {@code GET {baseUrl}/payments/{paymentId}}, {@code Authorization: PortOne <apiSecret>}.
 */
@Component
@ConditionalOnProperty(name = "payment.webhook.provider", havingValue = "portone")
public class PortOnePaymentClient {

    private final RestClient restClient;

    public PortOnePaymentClient(PaymentProperties properties, RestClient.Builder builder) {
        String apiSecret = properties.portone().apiSecret();
        if (apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("payment.portone.api-secret 이 없다 — 결제 조회 대조를 할 수 없다");
        }
        this.restClient = builder.baseUrl(properties.portone().baseUrl())
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
    }

    public PortOnePayment get(String paymentId) {
        JsonNode body = restClient.get().uri("/payments/{paymentId}", paymentId).retrieve().body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("PortOne 결제 조회 응답이 비었다: " + paymentId);
        }
        JsonNode amount = body.path("amount");
        return new PortOnePayment(body.path("id").asString(null), body.path("status").asString(null),
                body.path("storeId").asString(null), amount.path("total").isMissingNode() ? null : amount.path("total").asLong(),
                body.path("currency").asString(null));
    }

    public record PortOnePayment(String paymentId, String status, String storeId, Long totalAmount, String currency) {
    }
}
