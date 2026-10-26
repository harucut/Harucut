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
    private final String storeId;

    public PortOnePaymentClient(PaymentProperties properties, RestClient.Builder builder) {
        String apiSecret = properties.portone().apiSecret();
        if (apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("payment.portone.api-secret 이 없다 — 결제 조회 대조를 할 수 없다");
        }
        String storeId = properties.portone().storeId();
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalStateException("payment.portone.store-id 가 없다 — 어느 상점의 결제인지 정할 수 없다");
        }
        this.storeId = storeId;
        this.restClient = builder.baseUrl(properties.portone().baseUrl())
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
    }

    public PortOnePayment get(String paymentId) {
        // storeId 를 빼면 PortOne 은 API 비밀의 상점으로 조회한다 — 설정한 상점이 다르면 엉뚱한 상점을 보게 되므로 늘 보낸다
        JsonNode body = restClient.get()
                .uri(uri -> uri.path("/payments/{paymentId}").queryParam("storeId", storeId).build(paymentId))
                .retrieve().body(JsonNode.class);
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
