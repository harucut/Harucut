package com.harucut.subscription.store;

import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.gateway.dto.BillingKeyResult;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.payment.service.PaymentTransactionService;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.coupon.entity.Coupon;
import com.harucut.coupon.entity.UserCoupon;
import com.harucut.coupon.enums.UserCouponStatus;
import com.harucut.coupon.repository.CouponRepository;
import com.harucut.coupon.repository.UserCouponRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final String PRO = "harucut.pro.monthly";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private StorePurchaseRepository purchaseRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private UserCouponRepository userCouponRepository;
    @Autowired private PaymentTransactionService paymentTransactionService;
    @Autowired private PaymentOrderRepository paymentOrderRepository;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> coupons = new ArrayList<>();
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
            jdbcTemplate.update("delete from user_coupon where user_id = ?", id);
            jdbcTemplate.update("delete from payment where payment_order_id in (select payment_order_id from payment_order where user_id = ?)", id);
            jdbcTemplate.update("delete from payment_order where user_id = ?", id);
            jdbcTemplate.update("delete from billing_key where user_id = ?", id);
            jdbcTemplate.update("delete from store_purchase where user_id = ?", id);
            jdbcTemplate.update("delete from user_subscription where user_id = ?", id);
            jdbcTemplate.update("delete from users where user_id = ?", id);
        });
        coupons.forEach(id -> jdbcTemplate.update("delete from coupon where coupon_id = ?", id));
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

    @Test
    @DisplayName("#43 P1: 환불(REVOKED)·자동갱신 끔(CANCELED)으로 기록된 구매의 옛 증빙을 다시 내도 되돌리지 못한다 — 등록 SUBS-007, 복원은 건너뜀, 더 늦게 끝나는 새 갱신 증빙만 반영")
    void staleProofCannotRevertStoredState() {
        String proof = proof("tx-20", PLUS, later, Map.of());
        assertThat(purchase(user, "APP_STORE", PLUS, proof)).hasStatusOk();
        notify("n-20", "DID_CHANGE_RENEWAL_STATUS", transaction("tx-20", later, Map.of("autoRenew", false)));
        assertThat(purchase(user, "APP_STORE", PLUS, proof)).hasStatusOk();   // 같은 기한의 옛 증빙 — 저장 상태 유지
        assertThat(subscription(user).getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(subscription(user).isAutoRenew()).isFalse();

        notify("n-21", "REFUND", transaction("tx-20", later, Map.of("revoked", true)));
        assertThat(purchase(user, "APP_STORE", PLUS, proof)).hasStatus(400).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("SUBS-007"));
        assertThat(restore(user, "APP_STORE", List.of(Map.of("productId", PLUS, "proof", proof)))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("BASIC"));
        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(StorePurchaseStatus.REVOKED));

        // 환불 뒤 같은 원거래로 다시 구독(더 늦게 끝나는 새 거래) — 반영된다
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-20", PLUS, later.plusMonths(1), Map.of()))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("PLUS"));
    }

    @Test
    @DisplayName("#43 P2: 저장 PRO 구매에 늦게 온 옛 PLUS 알림 — 상품·기한을 남기면 등급도 PRO 를 남긴다")
    void staleNotificationKeepsStoredTier() {
        assertThat(purchase(user, "APP_STORE", PRO, proof("tx-21", PRO, later, Map.of()))).hasStatusOk();

        notify("n-22", "DID_RENEW", transaction("tx-21", later.minusDays(10), Map.of(), PLUS));

        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getProductId()).isEqualTo(PRO);
                    assertThat(row.getPlanTier()).isEqualTo(PlanTier.PRO);
                });
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.PRO);
    }

    @Test
    @DisplayName("#43 P2: 쿠폰을 예약한 스토어 구독자에게 만료 알림이 먼저 오면 강등 대신 쿠폰을 이어 받는다(만료 배치와 같은 규칙)")
    void expiryActivatesReservedCoupon() throws InterruptedException {
        LocalDateTime soon = LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-22", PLUS, soon, Map.of()))).hasStatusOk();
        Coupon coupon = couponRepository.save(Coupon.create("스토어 만료 쿠폰", "STORE-EXPIRE-" + System.nanoTime() % 100000, PlanTier.PRO, null, null));
        coupons.add(coupon.getId());
        UserCoupon reserved = userCouponRepository.save(UserCoupon.reserved(coupon, user.getId(), LocalDateTime.now()));
        UserSubscription subscription = subscription(user);
        subscription.reserveGrant(reserved.getId());
        subscriptionRepository.save(subscription);

        Thread.sleep(2500);   // 그 기한이 지났다
        notify("n-23", "EXPIRED", transaction("tx-22", soon, Map.of()));

        UserSubscription granted = subscription(user);
        assertThat(granted.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.GRANTED);
        assertThat(granted.getPlanTier()).isEqualTo(PlanTier.PRO);
        assertThat(granted.getSource()).isEqualTo(SubscriptionSource.COUPON);
        assertThat(granted.getReservedUserCouponId()).isNull();
        assertThat(userCouponRepository.findById(reserved.getId()).orElseThrow().getStatus()).isEqualTo(UserCouponStatus.REDEEMED);
    }

    @Test
    @DisplayName("#43 재리뷰 P1: 환불 알림을 먼저 처리한 뒤 처리 순서가 뒤바뀐 옛 갱신 알림(같은 기한·더 이른 기한)은 환불을 되돌리지 못한다 — 애플의 환불 취소(REFUND_REVERSED)·더 늦게 끝나는 새 거래만 푼다")
    void staleRenewalAfterRefundKeepsRevoked() {
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-30", PLUS, later, Map.of()))).hasStatusOk();
        notify("n-30", "REFUND", transaction("tx-30", later, Map.of("revoked", true)));

        notify("n-31", "DID_RENEW", transaction("tx-30", later, Map.of()));
        notify("n-32", "DID_RENEW", transaction("tx-30", later.minusDays(5), Map.of()));
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.BASIC);
        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(StorePurchaseStatus.REVOKED));

        notify("n-33", "REFUND_REVERSED", transaction("tx-30", later, Map.of()));
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.PLUS);
    }

    @Test
    @DisplayName("#43 재리뷰2 P2: 다음 기간 갱신이 저장된 뒤 이전 기간 거래의 환불 알림 — 지금 기간은 그대로 유지")
    void refundOfEarlierPeriodKeepsCurrent() {
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-50", PLUS, later, Map.of()))).hasStatusOk();
        LocalDateTime next = later.plusMonths(1);
        notify("n-50", "DID_RENEW", transaction("tx-50", next, Map.of()));

        notify("n-51", "REFUND", transaction("tx-50", later, Map.of("revoked", true)));   // 이전 기간 거래

        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.PLUS);
        assertThat(subscription(user).getCurrentPeriodEnd()).isEqualTo(next);
        assertThat(purchaseRepository.findAllByUserIdAndPlatform(user.getId(), StorePlatform.APP_STORE)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(StorePurchaseStatus.ACTIVE));
        // 지금 기간의 환불은 그대로 반영
        notify("n-52", "REFUND", transaction("tx-50", next, Map.of("revoked", true)));
        assertThat(subscription(user).getPlanTier()).isEqualTo(PlanTier.BASIC);
    }

    @Test
    @DisplayName("#43 재리뷰2 P1: 첫 웹 결제 — 출처 검사 뒤 스토어로 등록됐으면 주문을 만들지 않고 SUBS-009, 주문을 만든 뒤 등록됐으면 청구 성공해도 스토어 구독을 웹으로 덮지 않는다")
    void initialWebChargeVersusStore() {
        // 1) 주문 만들기 전에 스토어 등록
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-60", PLUS, later, Map.of()))).hasStatusOk();
        assertThatThrownBy(() -> paymentTransactionService.createInitialOrder(user.getId(), PlanTier.PRO, 9900, "init-60",
                PgProvider.MOCK, BillingKeyResult.success("bk-60", "1234-****")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(SubscriptionErrorCode.SUBSCRIBED_ELSEWHERE);
                    assertThat(e.getData()).isEqualTo(Map.of("source", SubscriptionSource.APP_STORE));
                });
        assertThat(paymentOrderRepository.findByIdempotencyKey("init-60")).isEmpty();

        // 2) 주문을 만든 뒤(청구 중) 스토어 등록 — 주문은 지워지지 않고, 청구 성공은 기록만 하고 스토어 구독 유지
        User other = save("notify-init-" + System.nanoTime() + "@harucut.com");
        PaymentTransactionService.CreatedOrder created = paymentTransactionService.createInitialOrder(other.getId(), PlanTier.PRO, 9900,
                "init-61", PgProvider.MOCK, BillingKeyResult.success("bk-61", "1234-****"));
        assertThat(paymentOrderRepository.findById(created.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(purchase(other, "APP_STORE", PLUS, proof("tx-61", PLUS, later, Map.of()))).hasStatusOk();
        assertThat(paymentOrderRepository.findById(created.orderId())).isPresent();

        PaymentTransactionService.ChargeApplyResult applied = paymentTransactionService.applyInitialChargeResult(other.getId(),
                created.orderId(), PaymentResult.success("pg-61", LocalDateTime.now()), LocalDateTime.now());

        assertThat(applied.supersededBy()).isEqualTo(SubscriptionSource.APP_STORE);
        assertThat(paymentOrderRepository.findById(created.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(subscription(other).getSource()).isEqualTo(SubscriptionSource.APP_STORE);
        assertThat(subscription(other).getPlanTier()).isEqualTo(PlanTier.PLUS);
    }

    private String proof(String storeKey, String productId, LocalDateTime expiresAt, Map<String, Object> extra) {
        return "mock:" + objectMapper.writeValueAsString(transaction(storeKey, expiresAt, extra, productId));
    }

    private Map<String, Object> transaction(String storeKey, LocalDateTime expiresAt, Map<String, Object> extra) {
        return transaction(storeKey, expiresAt, extra, PLUS);
    }

    private MvcTestResult restore(User who, String platform, List<Map<String, String>> items) {
        return mvc.post().uri("/api/auth/subscriptions/store-purchases/restore").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("platform", platform, "items", items)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService
                        .createAccessToken(who.getPublicId(), who.getUserRole(), who.getUserStatus()).value())
                .exchange();
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
