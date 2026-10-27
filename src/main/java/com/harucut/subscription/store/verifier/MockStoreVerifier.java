package com.harucut.subscription.store.verifier;

import com.harucut.subscription.store.StoreEnvironment;
import com.harucut.subscription.store.StorePlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * 로컬·테스트용 검증기 — 서명을 보지 않는다. 증빙은 {@code mock:} 뒤의 JSON:
 * {@code {"storeKey":"...","productId":"...","expiresAt":"2026-12-01T00:00:00","autoRenew":true,"revoked":false,
 * "billingRetry":false,"accountToken":"...","signedAt":"2026-11-01T00:00:00"}} — signedAt 이 없으면 지금(검증 시각). 알림은 {@code {"notificationId":"...","type":"...","transaction":{...},"autoRenew":true}}.
 * 운영에서 켜지면 StoreMockGuard 가 기동을 막는다.
 */
@Component
@ConditionalOnProperty(name = "billing.store.verifier", havingValue = "mock", matchIfMissing = true)
public class MockStoreVerifier implements AppStoreVerifier, PlayStoreVerifier {

    private static final String PREFIX = "mock:";

    private final ObjectMapper objectMapper;

    public MockStoreVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public VerifiedPurchase verifyTransaction(String signedTransaction) {
        return purchase(StorePlatform.APP_STORE, read(signedTransaction), null);
    }

    @Override
    public AppStoreNotification verifyNotification(String signedPayload) {
        JsonNode node = read(signedPayload);
        JsonNode transaction = node.path("transaction");
        return new AppStoreNotification(node.path("notificationId").asString(), node.path("type").asString(),
                node.path("subtype").isMissingNode() ? null : node.path("subtype").asString(),
                transaction.isMissingNode() ? null : purchase(StorePlatform.APP_STORE, transaction, null),
                node.path("autoRenew").isMissingNode() ? null : node.path("autoRenew").asBoolean());
    }

    @Override
    public VerifiedPurchase verify(String productId, String purchaseToken) {
        return purchase(StorePlatform.PLAY_STORE, read(purchaseToken), purchaseToken);
    }

    // 구글은 토큰 자체가 열쇠다 — 목에서는 storeKey 가 없으면 증빙 문자열을 그대로 쓴다
    private static VerifiedPurchase purchase(StorePlatform platform, JsonNode node, String fallbackKey) {
        String storeKey = node.path("storeKey").isMissingNode() ? fallbackKey : node.path("storeKey").asString();
        if (storeKey == null || node.path("productId").isMissingNode() || node.path("expiresAt").isMissingNode()) {
            throw new StoreVerificationException("목 증빙에 storeKey·productId·expiresAt 이 필요하다");
        }
        return new VerifiedPurchase(platform, storeKey, node.path("productId").asString(),
                LocalDateTime.parse(node.path("expiresAt").asString()), node.path("autoRenew").asBoolean(true),
                node.path("revoked").asBoolean(false), node.path("billingRetry").asBoolean(false), StoreEnvironment.SANDBOX,
                node.path("accountToken").isMissingNode() ? null : node.path("accountToken").asString(),
                node.path("signedAt").isMissingNode() ? LocalDateTime.now() : LocalDateTime.parse(node.path("signedAt").asString()));
    }

    private JsonNode read(String proof) {
        if (proof == null || !proof.startsWith(PREFIX)) {
            throw new StoreVerificationException("목 증빙은 mock: 으로 시작한다");
        }
        try {
            return objectMapper.readTree(proof.substring(PREFIX.length()));
        } catch (JacksonException e) {
            throw new StoreVerificationException("목 증빙 JSON 이 아니다", e);
        }
    }
}
