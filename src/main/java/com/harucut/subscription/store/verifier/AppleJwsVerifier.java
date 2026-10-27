package com.harucut.subscription.store.verifier;

import com.harucut.subscription.store.StoreEnvironment;
import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.config.StoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * App Store 서명 검증 (ADR-0011 §2) — StoreKit 2 거래와 Server Notifications v2 는 JWS(ES256)이고, 헤더 x5c 의 인증서 체인이
 * Apple 루트(설정 {@code billing.store.apple.root-certificates})까지 이어져야 한다. 네트워크 없이 검증한다.
 * <ol>
 *   <li>x5c 체인을 신뢰 루트로 PKIX 검증(폐기 확인 없음) — 서명 인증서 OID 1.2.840.113635.100.6.11.1, 중간 인증서 OID 1.2.840.113635.100.6.2.1</li>
 *   <li>서명 인증서의 공개키로 JWS 서명(ES256, R‖S) 확인</li>
 *   <li>번들 id·환경(샌드박스 허용 여부)·자동 갱신 구독인지 확인</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "billing.store.verifier", havingValue = "real")
public class AppleJwsVerifier implements AppStoreVerifier {

    static final String LEAF_OID = "1.2.840.113635.100.6.11.1";
    static final String INTERMEDIATE_OID = "1.2.840.113635.100.6.2.1";
    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    private final StoreProperties.Apple properties;
    private final Set<TrustAnchor> anchors;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AppleJwsVerifier(StoreProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties.apple();
        this.anchors = anchors(properties.apple().rootCertificates());
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public VerifiedPurchase verifyTransaction(String signedTransaction) {
        // 거래 JWS 에는 자동 갱신 상태가 없다 — 방금 산(또는 복원한) 구독은 켜진 것으로 보고, 끈 것은 알림이 바로잡는다
        return transaction(verified(signedTransaction), true, false);
    }

    @Override
    public AppStoreNotification verifyNotification(String signedPayload) {
        JsonNode payload = verified(signedPayload);
        JsonNode data = payload.path("data");
        if (!data.path("bundleId").isMissingNode() && !properties.bundleId().equals(data.path("bundleId").asString())) {
            throw new StoreVerificationException("알림의 번들 id 가 다르다");
        }
        Boolean autoRenew = null;
        boolean billingRetry = false;
        if (!data.path("signedRenewalInfo").isMissingNode()) {
            JsonNode renewal = verified(data.path("signedRenewalInfo").asString());
            autoRenew = renewal.path("autoRenewStatus").asInt(1) == 1;
            billingRetry = renewal.path("isInBillingRetryPeriod").asBoolean(false);
        }
        VerifiedPurchase transaction = data.path("signedTransactionInfo").isMissingNode() ? null
                : transaction(verified(data.path("signedTransactionInfo").asString()), autoRenew == null || autoRenew, billingRetry);
        return new AppStoreNotification(payload.path("notificationUUID").asString(), payload.path("notificationType").asString(),
                payload.path("subtype").isMissingNode() ? null : payload.path("subtype").asString(), transaction, autoRenew);
    }

    private VerifiedPurchase transaction(JsonNode payload, boolean autoRenew, boolean billingRetry) {
        if (!properties.bundleId().equals(payload.path("bundleId").asString())) {
            throw new StoreVerificationException("거래의 번들 id 가 다르다");
        }
        if (!"Auto-Renewable Subscription".equals(payload.path("type").asString())) {
            throw new StoreVerificationException("자동 갱신 구독 거래가 아니다");
        }
        StoreEnvironment environment = "Production".equals(payload.path("environment").asString())
                ? StoreEnvironment.PRODUCTION : StoreEnvironment.SANDBOX;
        if (environment == StoreEnvironment.SANDBOX && !properties.allowSandbox()) {
            throw new StoreVerificationException("샌드박스 거래를 받지 않는다");
        }
        if (payload.path("originalTransactionId").isMissingNode() || payload.path("expiresDate").isMissingNode()) {
            throw new StoreVerificationException("원거래 id·만료 시각이 없다");
        }
        return new VerifiedPurchase(StorePlatform.APP_STORE, payload.path("originalTransactionId").asString(),
                payload.path("productId").asString(), time(payload.path("expiresDate").asLong()), autoRenew,
                !payload.path("revocationDate").isMissingNode(), billingRetry, environment,
                payload.path("appAccountToken").isMissingNode() ? null : payload.path("appAccountToken").asString());
    }

    // 서명·체인을 확인한 JWS 의 본문
    JsonNode verified(String jws) {
        if (anchors.isEmpty()) {
            throw new StoreVerificationException("Apple 루트 인증서가 설정되지 않았다(billing.store.apple.root-certificates)");
        }
        String[] parts = jws == null ? new String[0] : jws.split("\\.");
        if (parts.length != 3) {
            throw new StoreVerificationException("JWS 형식이 아니다");
        }
        try {
            JsonNode header = objectMapper.readTree(URL.decode(parts[0]));
            if (!"ES256".equals(header.path("alg").asString())) {
                throw new StoreVerificationException("ES256 서명이 아니다");
            }
            List<X509Certificate> chain = chain(header.path("x5c"));
            validate(chain);
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initVerify(chain.getFirst().getPublicKey());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(URL.decode(parts[2]))) {
                throw new StoreVerificationException("JWS 서명이 맞지 않는다");
            }
            return objectMapper.readTree(URL.decode(parts[1]));
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException e) {
            throw new StoreVerificationException("JWS 검증 실패", e);
        }
    }

    private static List<X509Certificate> chain(JsonNode x5c) throws GeneralSecurityException {
        if (!x5c.isArray() || x5c.size() < 2) {
            throw new StoreVerificationException("x5c 체인이 없다");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (JsonNode encoded : x5c) {
            chain.add((X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(encoded.asString()))));
        }
        return chain;
    }

    private void validate(List<X509Certificate> chain) throws GeneralSecurityException {
        if (chain.get(0).getExtensionValue(LEAF_OID) == null || chain.get(1).getExtensionValue(INTERMEDIATE_OID) == null) {
            throw new StoreVerificationException("Apple 서명·중간 인증서 표시(OID)가 없다");
        }
        // 루트는 신뢰 앵커로만 — x5c 에 실려 와도 경로에서 뺀다
        Set<X509Certificate> roots = anchors.stream().map(TrustAnchor::getTrustedCert).collect(Collectors.toSet());
        List<X509Certificate> path = chain.stream().filter(certificate -> !roots.contains(certificate)).toList();
        CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
        PKIXParameters parameters = new PKIXParameters(anchors);
        parameters.setRevocationEnabled(false);
        parameters.setDate(Date.from(clock.instant()));
        CertPathValidator.getInstance("PKIX").validate(certPath, parameters);
    }

    private LocalDateTime time(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), clock.getZone());
    }

    private static Set<TrustAnchor> anchors(String pem) {
        if (pem == null || pem.isBlank()) {
            return Set.of();
        }
        try {
            return CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                    .stream().map(certificate -> new TrustAnchor((X509Certificate) certificate, null)).collect(Collectors.toSet());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("billing.store.apple.root-certificates 를 읽을 수 없다", e);
        }
    }
}
