package com.harucut.subscription.store;

import com.harucut.auth.service.JwtTokenService;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.store.repository.StorePurchaseRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 스토어 알림 (ADR-0011 §2) — 웹훅은 바깥 트랜잭션 없이 오고, 반영 트랜잭션은 구독 행 잠금으로 시작한다. 그래서 이 클래스는
 * 테스트 트랜잭션을 두지 않는다(요청마다 커밋) — 끝나면 만든 행을 지운다. 증빙은 목 검증기 형식("mock:" + JSON).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("스토어 알림 — 통합(요청마다 커밋)")
class StoreNotificationIntegrationTest {

    private static final String PLUS = "harucut.plus.monthly";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private StorePurchaseRepository purchaseRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> users = new ArrayList<>();
    private User user;
    // DB 왕복에서 마이크로초가 잘리지 않게
    private final LocalDateTime later = LocalDateTime.now().plusDays(30).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void user() {
        user = save("notify-" + System.nanoTime() + "@harucut.com");
    }

    @AfterEach
    void cleanUp() {
        users.forEach(id -> {
            jdbcTemplate.update("delete from store_purchase where user_id = ?", id);
            jdbcTemplate.update("delete from user_subscription where user_id = ?", id);
            jdbcTemplate.update("delete from users where user_id = ?", id);
        });
        jdbcTemplate.update("delete from webhook_receipt where provider in ('APP_STORE', 'PLAY_STORE')");
    }

    @Test
    @DisplayName("앱스토어 알림: 자동갱신 끔 → CANCELED, 갱신 → 기한 연장, 늦게 온 옛 알림은 기한을 되돌리지 않음, 같은 알림은 한 번, 환불 → BASIC")
    void appStoreNotifications() {
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-12", PLUS, later, Map.of()))).hasStatusOk();

        notify("n-1", "DID_CHANGE_RENEWAL_STATUS", transaction("tx-12", later, Map.of("autoRenew", false)));
        UserSubscription canceled = subscription(user);
        assertThat(canceled.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(canceled.isAutoRenew()).isFalse();

        LocalDateTime renewed = later.plusMonths(1);
        notify("n-2", "DID_RENEW", transaction("tx-12", renewed, Map.of()));
        assertThat(subscription(user).getCurrentPeriodEnd()).isEqualTo(renewed);
        assertThat(subscription(user).getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        notify("n-3", "DID_RENEW", transaction("tx-12", later, Map.of()));   // 옛 알림
        assertThat(subscription(user).getCurrentPeriodEnd()).isEqualTo(renewed);

        notify("n-4", "REFUND", transaction("tx-12", renewed, Map.of("revoked", true)));
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.BASIC);
        assertThat(subscription(user).getSource()).isNull();
        notify("n-2", "DID_RENEW", transaction("tx-12", renewed.plusMonths(1), Map.of()));   // 재전송 — 이미 처리
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.BASIC);
        // 등록되지 않은 구매의 알림은 건너뛴다
        notify("n-5", "SUBSCRIBED", transaction("tx-unknown", later, Map.of()));
        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).hasSize(1);
        assertThat(postWebhook("/api/payments/webhooks/app-store", "{\"signedPayload\":\"forged\"}")).hasStatus(400);
    }

    @Test
    @DisplayName("앱스토어 알림: 그 사이 웹으로 옮긴 계정은 구독을 건드리지 않고 구매만 갱신한다")
    void notificationAfterMovingToWeb() {
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-13", PLUS, later, Map.of()))).hasStatusOk();
        UserSubscription subscription = subscription(user);
        subscription.expireToFree();
        subscription.activatePaid(PlanTier.PRO, LocalDateTime.now(), later.plusDays(3));
        subscriptionRepository.save(subscription);

        notify("n-6", "DID_RENEW", transaction("tx-13", later.plusMonths(1), Map.of()));

        assertThat(subscription(user).getSource()).isEqualTo(SubscriptionSource.WEB);
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.PRO);
        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).singleElement()
                .satisfies(row -> assertThat(row.getExpiresAt()).isEqualTo(later.plusMonths(1)));
    }

    @Test
    @DisplayName("구글 RTDN: 푸시 토큰이 맞아야 하고(아니면 PAY-008), 알림이 오면 Play 로 다시 조회한 상태를 반영한다")
    void googlePlayNotifications() {
        String token = proof("play-token-1", PLUS, later, Map.of());
        assertThat(purchase(user, "PLAY_STORE", PLUS, token)).hasStatusOk();

        assertThat(postWebhook("/api/payments/webhooks/google-play?token=wrong", rtdn(token))).hasStatus(400);
        assertThat(postWebhook("/api/payments/webhooks/google-play", rtdn(token))).hasStatus(400);
        // 목 검증기는 토큰 문자열의 JSON 을 「Play 가 돌려준 상태」로 읽는다 — 같은 토큰이라 상태는 그대로 ACTIVE
        assertThat(postWebhook("/api/payments/webhooks/google-play?token=test-push-token", rtdn(token))).hasStatusOk();
        assertThat(subscription(user).getSource()).isEqualTo(SubscriptionSource.PLAY_STORE);
        assertThat(subscription(user).getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        // 모르는 토큰은 건너뛴다
        assertThat(postWebhook("/api/payments/webhooks/google-play?token=test-push-token", rtdn("mock:{}"))).hasStatusOk();
    }

    private String proof(String storeKey, String productId, LocalDateTime expiresAt, Map<String, Object> extra) {
        return "mock:" + objectMapper.writeValueAsString(transaction(storeKey, expiresAt, extra, productId));
    }

    private Map<String, Object> transaction(String storeKey, LocalDateTime expiresAt, Map<String, Object> extra) {
        return transaction(storeKey, expiresAt, extra, PLUS);
    }

    private static Map<String, Object> transaction(String storeKey, LocalDateTime expiresAt, Map<String, Object> extra, String productId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeKey", storeKey);
        body.put("productId", productId);
        body.put("expiresAt", expiresAt.toString());
        body.putAll(extra);
        return body;
    }

    private void notify(String id, String type, Map<String, Object> transaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", id);
        payload.put("type", type);
        payload.put("transaction", transaction);
        if (transaction.containsKey("autoRenew")) {
            payload.put("autoRenew", transaction.get("autoRenew"));
        }
        String body = objectMapper.writeValueAsString(Map.of("signedPayload", "mock:" + objectMapper.writeValueAsString(payload)));
        assertThat(postWebhook("/api/payments/webhooks/app-store", body)).hasStatusOk();
    }

    private String rtdn(String purchaseToken) {
        String data = objectMapper.writeValueAsString(Map.of("packageName", "com.harucut.app",
                "subscriptionNotification", Map.of("notificationType", 2, "purchaseToken", purchaseToken, "subscriptionId", PLUS)));
        return objectMapper.writeValueAsString(Map.of("message", Map.of("data", Base64.getEncoder().encodeToString(data.getBytes()),
                "messageId", "m-1")));
    }

    private MvcTestResult purchase(User who, String platform, String productId, String proof) {
        return mvc.post().uri("/api/auth/subscriptions/store-purchases").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("platform", platform, "productId", productId, "proof", proof)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService
                        .createAccessToken(who.getPublicId(), who.getUserRole(), who.getUserStatus()).value())
                .exchange();
    }

    private MvcTestResult postWebhook(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private UserSubscription subscription(User who) {
        return subscriptionRepository.findByUserId(who.getId()).orElseThrow();
    }

    private User save(String email) {
        User saved = userRepository.save(User.localUser(email, "e", "알림"));
        subscriptionRepository.save(UserSubscription.createBasic(saved.getId()));
        users.add(saved.getId());
        return saved;
    }
}
