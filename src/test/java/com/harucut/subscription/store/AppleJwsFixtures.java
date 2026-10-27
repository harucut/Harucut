package com.harucut.subscription.store;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 테스트 전용 체인(src/test/resources/store/apple)으로 App Store 형식의 JWS 를 만든다 */
public final class AppleJwsFixtures {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private AppleJwsFixtures() {
    }

    public static String pem(String name) {
        try (InputStream in = AppleJwsFixtures.class.getResourceAsStream("/store/apple/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** x5c = [서명, 중간] — 서명 인증서 키로 ES256 */
    public static String sign(Map<String, Object> payload) {
        return sign(payload, List.of("leaf.pem", "intermediate.pem"));
    }

    public static String sign(Map<String, Object> payload, List<String> chain) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("x5c", chain.stream().map(AppleJwsFixtures::der).toList());
            String signingInput = URL.encodeToString(JSON.writeValueAsBytes(header)) + "." + URL.encodeToString(JSON.writeValueAsBytes(payload));
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(leafKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 앱스토어 거래 본문(자동 갱신 구독) */
    public static Map<String, Object> transaction(String originalTransactionId, String productId, long expiresMillis) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalTransactionId", originalTransactionId);
        payload.put("transactionId", originalTransactionId + "-1");
        payload.put("bundleId", "com.harucut.app");
        payload.put("productId", productId);
        payload.put("type", "Auto-Renewable Subscription");
        payload.put("expiresDate", expiresMillis);
        payload.put("environment", "Production");
        payload.put("signedDate", java.time.Instant.parse("2027-01-05T00:00:00Z").toEpochMilli());
        return payload;
    }

    private static String der(String name) {
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(pem(name).getBytes(StandardCharsets.US_ASCII)));
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivateKey leafKey() throws GeneralSecurityException {
        String body = pem("leaf.key.pem").replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }
}
