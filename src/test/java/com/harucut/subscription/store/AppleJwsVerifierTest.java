package com.harucut.subscription.store;

import com.harucut.subscription.store.config.StoreProperties;
import com.harucut.subscription.store.verifier.AppStoreNotification;
import com.harucut.subscription.store.verifier.AppleJwsVerifier;
import com.harucut.subscription.store.verifier.StoreVerificationException;
import com.harucut.subscription.store.verifier.VerifiedPurchase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.harucut.subscription.store.AppleJwsFixtures.pem;
import static com.harucut.subscription.store.AppleJwsFixtures.sign;
import static com.harucut.subscription.store.AppleJwsFixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("App Store JWS 검증 — x5c 체인·서명·번들·환경")
class AppleJwsVerifierTest {

    // 테스트 인증서 유효 기간 안
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-10T00:00:00Z"), ZoneOffset.UTC);
    private static final long EXPIRES = Instant.parse("2027-02-10T00:00:00Z").toEpochMilli();

    @Test
    @DisplayName("테스트 루트까지 이어진 서명 거래 → 원거래 id·상품·만료·환경·계정 표시")
    void verifiesTransaction() {
        Map<String, Object> payload = transaction("2000001", "harucut.plus.monthly", EXPIRES);
        payload.put("appAccountToken", "4a1d6f0e-2b3c-3d4e-9f00-112233445566");

        VerifiedPurchase verified = verifier(pem("root.pem"), true).verifyTransaction(sign(payload));

        assertThat(verified.platform()).isEqualTo(StorePlatform.APP_STORE);
        assertThat(verified.storeKey()).isEqualTo("2000001");
        assertThat(verified.productId()).isEqualTo("harucut.plus.monthly");
        assertThat(verified.expiresAt()).isEqualTo(LocalDateTime.of(2027, 2, 10, 0, 0));
        assertThat(verified.environment()).isEqualTo(StoreEnvironment.PRODUCTION);
        assertThat(verified.accountToken()).isEqualTo("4a1d6f0e-2b3c-3d4e-9f00-112233445566");
        assertThat(verified.revoked()).isFalse();
        // x5c 에 루트가 실려 와도 된다
        assertThat(verifier(pem("root.pem"), true).verifyTransaction(sign(payload, List.of("leaf.pem", "intermediate.pem", "root.pem")))
                .storeKey()).isEqualTo("2000001");
    }

    @Test
    @DisplayName("신뢰하지 않는 루트·루트 미설정·중간 인증서 없는 체인·본문 변조·번들 다름·구독 아님·샌드박스 금지는 모두 거절")
    void rejects() {
        String good = sign(transaction("2000002", "harucut.plus.monthly", EXPIRES));
        assertThatThrownBy(() -> verifier(pem("other-root.pem"), true).verifyTransaction(good)).isInstanceOf(StoreVerificationException.class);
        assertThatThrownBy(() -> verifier("", true).verifyTransaction(good)).isInstanceOf(StoreVerificationException.class);
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyTransaction(
                sign(transaction("2000002", "harucut.plus.monthly", EXPIRES), List.of("leaf.pem", "root.pem"))))
                .isInstanceOf(StoreVerificationException.class);

        String[] parts = good.split("\\.");
        Map<String, Object> forged = transaction("2000002", "harucut.pro.monthly", EXPIRES);
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JsonMapper.builder().build().writeValueAsBytes(forged)) + "." + parts[2];
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyTransaction(tampered)).isInstanceOf(StoreVerificationException.class);

        Map<String, Object> otherBundle = transaction("2000003", "harucut.plus.monthly", EXPIRES);
        otherBundle.put("bundleId", "com.evil.app");
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyTransaction(sign(otherBundle))).isInstanceOf(StoreVerificationException.class);

        Map<String, Object> consumable = transaction("2000004", "harucut.plus.monthly", EXPIRES);
        consumable.put("type", "Consumable");
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyTransaction(sign(consumable))).isInstanceOf(StoreVerificationException.class);

        Map<String, Object> sandbox = transaction("2000005", "harucut.plus.monthly", EXPIRES);
        sandbox.put("environment", "Sandbox");
        assertThat(verifier(pem("root.pem"), true).verifyTransaction(sign(sandbox)).environment()).isEqualTo(StoreEnvironment.SANDBOX);
        assertThatThrownBy(() -> verifier(pem("root.pem"), false).verifyTransaction(sign(sandbox))).isInstanceOf(StoreVerificationException.class);
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyTransaction("not-a-jws")).isInstanceOf(StoreVerificationException.class);
    }

    @Test
    @DisplayName("알림 v2: 중첩된 거래·갱신 정보도 서명을 확인하고, 자동갱신 끔·결제 재시도·환불을 읽는다")
    void verifiesNotification() {
        Map<String, Object> revokedTransaction = transaction("2000006", "harucut.plus.monthly", EXPIRES);
        revokedTransaction.put("revocationDate", Instant.parse("2027-01-09T00:00:00Z").toEpochMilli());
        Map<String, Object> renewal = new LinkedHashMap<>();
        renewal.put("autoRenewStatus", 0);
        renewal.put("isInBillingRetryPeriod", true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bundleId", "com.harucut.app");
        data.put("signedTransactionInfo", sign(revokedTransaction));
        data.put("signedRenewalInfo", sign(renewal));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationType", "REFUND");
        payload.put("notificationUUID", "n-1");
        payload.put("data", data);

        AppStoreNotification notification = verifier(pem("root.pem"), true).verifyNotification(sign(payload));

        assertThat(notification.notificationId()).isEqualTo("n-1");
        assertThat(notification.type()).isEqualTo("REFUND");
        assertThat(notification.autoRenew()).isFalse();
        assertThat(notification.transaction().revoked()).isTrue();
        assertThat(notification.transaction().autoRenew()).isFalse();
        assertThat(notification.transaction().billingRetry()).isTrue();

        // 중첩 거래가 다른 체인으로 서명됐으면 거절
        data.put("signedTransactionInfo", sign(revokedTransaction, List.of("leaf.pem", "root.pem")));
        assertThatThrownBy(() -> verifier(pem("root.pem"), true).verifyNotification(sign(payload)))
                .isInstanceOf(StoreVerificationException.class);
    }

    private static AppleJwsVerifier verifier(String rootPem, boolean allowSandbox) {
        StoreProperties properties = new StoreProperties("real", new StoreProperties.Mock(false), Map.of(),
                new StoreProperties.Apple("com.harucut.app", rootPem, allowSandbox), new StoreProperties.Google("com.harucut.app", ""), 3);
        return new AppleJwsVerifier(properties, JsonMapper.builder().build(), CLOCK);
    }
}
