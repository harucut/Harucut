package com.harucut.payment.webhook;

import com.harucut.payment.config.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PortOneWebhookVerifier — Standard Webhooks")
class PortOneWebhookVerifierTest {

    private static final byte[] KEY = "harucut-test-webhook-secret-key!".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final long NOW = 1_790_000_000L;
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-1\"}}";

    private PortOneWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new PortOneWebhookVerifier(properties(SECRET), Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("원문 body 의 서명이 맞으면 통과, 한 글자라도 바뀌면 실패")
    void verifiesRawBody() {
        Map<String, String> headers = headers("msg_1", NOW, "v1," + sign("msg_1", NOW, BODY));
        assertThat(verifier.verify(BODY, headers)).isTrue();
        assertThat(verifier.verify(BODY.replace("pay-1", "pay-2"), headers)).isFalse();
        // 같은 내용이라도 재직렬화로 공백이 달라지면 서명이 깨진다 — 원문을 그대로 써야 하는 이유
        assertThat(verifier.verify(BODY.replace(",", ", "), headers)).isFalse();
    }

    @Test
    @DisplayName("허용 시각차(5분)를 넘으면 재전송으로 보고 실패")
    void rejectsStaleTimestamp() {
        long old = NOW - 301;
        assertThat(verifier.verify(BODY, headers("msg_1", old, "v1," + sign("msg_1", old, BODY)))).isFalse();
        long recent = NOW - 299;
        assertThat(verifier.verify(BODY, headers("msg_1", recent, "v1," + sign("msg_1", recent, BODY)))).isTrue();
    }

    @Test
    @DisplayName("서명 목록 중 하나만 맞으면 된다(비밀 교체 중), v1 이 아닌 것과 깨진 base64 는 건너뛴다")
    void multipleSignatures() {
        String good = sign("msg_1", NOW, BODY);
        assertThat(verifier.verify(BODY, headers("msg_1", NOW, "v1,AAAA v2," + good + " v1,%%% v1," + good))).isTrue();
        assertThat(verifier.verify(BODY, headers("msg_1", NOW, "v2," + good))).isFalse();
    }

    @Test
    @DisplayName("헤더가 빠졌거나 시각이 숫자가 아니면 실패, 비밀이 없으면 기동하지 않는다")
    void malformed() {
        assertThat(verifier.verify(BODY, Map.of("webhook-id", "msg_1"))).isFalse();
        assertThat(verifier.verify(BODY, Map.of("webhook-id", "msg_1", "webhook-timestamp", "abc", "webhook-signature", "v1,x"))).isFalse();
        assertThatThrownBy(() -> new PortOneWebhookVerifier(properties(null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, String> headers(String id, long timestamp, String signature) {
        return Map.of("webhook-id", id, "webhook-timestamp", Long.toString(timestamp), "webhook-signature", signature);
    }

    private static String sign(String id, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaymentProperties properties(String secret) {
        return new PaymentProperties(new PaymentProperties.Gateway("mock"), new PaymentProperties.Mock(false, false), 3,
                new PaymentProperties.Webhook("portone", 300), new PaymentProperties.PortOne(secret, "api", "store-1", "https://api.portone.io"));
    }
}
