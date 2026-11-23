package com.harucut.payment.webhook;

import com.harucut.payment.config.PaymentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

/**
 * PortOne V2 웹훅 — Standard Webhooks 서명 검증 (ADR-0008 §8). <b>실연동 미확인</b>(키·상점 필요).
 *
 * <p>서명 대상은 {@code webhook-id.webhook-timestamp.원문body}, 비밀은 {@code whsec_} 뒤의 base64 를 푼 바이트,
 * 알고리즘은 HMAC-SHA256. {@code webhook-signature} 는 공백으로 구분한 {@code v1,<base64>} 목록이라 그중 하나만 맞으면 된다
 * (비밀 교체 중에는 둘이 온다). 시각차가 허용 범위를 넘으면 재전송 공격으로 보고 거절한다. 비교는 상수 시간.
 */
@Component
@ConditionalOnProperty(name = "payment.webhook.provider", havingValue = "portone")
public class PortOneWebhookVerifier implements PaymentWebhookVerifier {

    private static final String PREFIX = "whsec_";

    private final byte[] secret;
    private final long toleranceSeconds;
    private final Clock clock;

    public PortOneWebhookVerifier(PaymentProperties properties, Clock clock) {
        String configured = properties.portone().webhookSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("payment.portone.webhook-secret 이 없다 — portone 웹훅 검증기를 켤 수 없다");
        }
        String encoded = configured.startsWith(PREFIX) ? configured.substring(PREFIX.length()) : configured;
        this.secret = Base64.getDecoder().decode(encoded);
        this.toleranceSeconds = properties.webhook().toleranceSeconds();
        this.clock = clock;
    }

    @Override
    public boolean verify(String rawBody, Map<String, String> headers) {
        String id = headers.get("webhook-id");
        String timestamp = headers.get("webhook-timestamp");
        String signatures = headers.get("webhook-signature");
        if (id == null || timestamp == null || signatures == null) {
            return false;
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - sentAt) > toleranceSeconds) {
            return false;
        }
        byte[] expected = sign(id + "." + timestamp.strip() + "." + rawBody);
        for (String candidate : signatures.strip().split(" ")) {
            int comma = candidate.indexOf(',');
            if (comma < 0 || !"v1".equals(candidate.substring(0, comma))) {
                continue;
            }
            byte[] given;
            try {
                given = Base64.getDecoder().decode(candidate.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, given)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String provider() {
        return "portone";
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없다", e);
        }
    }
}
