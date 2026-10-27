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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 스토어 구독 (ADR-0011 §2) — 목 검증기(증빙 = "mock:" + JSON)로 구매·복원·알림을 실제 보안 체인·컨트롤러·JPA 로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("스토어 구독 — 통합")
class StoreSubscriptionIntegrationTest {

    private static final String PLUS = "harucut.plus.monthly";
    private static final String PRO = "harucut.pro.monthly";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private StorePurchaseRepository purchaseRepository;

    private User user;
    private User other;
    private final LocalDateTime later = LocalDateTime.now().plusDays(30);

    @BeforeEach
    void users() {
        user = save("store@harucut.com");
        other = save("other@harucut.com");
    }

    @Test
    @DisplayName("구매 등록: 앱스토어 PLUS 로 구독 — source·기한·자동갱신·계정 표시, 같은 구매를 다시 보내도 같은 결과(한 행)")
    void purchase() {
        String proof = proof("tx-1", PLUS, later, Map.of("accountToken", StoreAccountTokens.of(user.getPublicId())));

        MvcTestResult result = purchase(user, "APP_STORE", PLUS, proof);

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("PLUS"))
                .hasPathSatisfying("$.data.status", v -> assertThat(v).isEqualTo("ACTIVE"))
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("APP_STORE"))
                .hasPathSatisfying("$.data.autoRenew", v -> assertThat(v).isEqualTo(true))
                .hasPathSatisfying("$.data.storeAccountToken", v -> assertThat(v).isEqualTo(StoreAccountTokens.of(user.getPublicId())));
        assertThat(purchase(user, "APP_STORE", PLUS, proof)).hasStatusOk();
        assertThat(purchaseRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getUserId()).isEqualTo(user.getId());
            assertThat(row.getStatus()).isEqualTo(StorePurchaseStatus.ACTIVE);
        });
        // 스토어 구독은 해지 API 로 끊을 수 없다 — 어디서 관리하는지 data.source
        assertThat(call(user, "/api/auth/subscriptions/cancel", "")).hasStatus(409).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("SUBS-009"))
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("APP_STORE"));
    }

    @Test
    @DisplayName("거절: 다른 계정에 묶인 구매·다른 계정 표시 SUBS-008, 웹 구독 중 SUBS-009(data.source=WEB), 모르는 상품 SUBS-010, 만료·위조·상품 불일치 SUBS-007")
    void rejections() {
        assertThat(purchase(user, "APP_STORE", PLUS, proof("tx-2", PLUS, later, Map.of()))).hasStatusOk();
        assertCode(purchase(other, "APP_STORE", PLUS, proof("tx-2", PLUS, later, Map.of())), 409, "SUBS-008");
        assertCode(purchase(other, "APP_STORE", PLUS, proof("tx-3", PLUS, later, Map.of("accountToken", StoreAccountTokens.of(user.getPublicId())))),
                409, "SUBS-008");

        User web = save("web@harucut.com");
        UserSubscription subscription = subscriptionRepository.findByUserId(web.getId()).orElseThrow();
        subscription.activatePaid(PlanTier.PRO, LocalDateTime.now().minusDays(1), later);
        assertThat(purchase(web, "PLAY_STORE", PLUS, proof("token-1", PLUS, later, Map.of()))).hasStatus(409).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("SUBS-009"))
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("WEB"));

        assertCode(purchase(other, "APP_STORE", "harucut.unknown", proof("tx-4", "harucut.unknown", later, Map.of())), 400, "SUBS-010");
        assertCode(purchase(other, "APP_STORE", PLUS, proof("tx-5", PLUS, LocalDateTime.now().minusDays(1), Map.of())), 400, "SUBS-007");
        assertCode(purchase(other, "APP_STORE", PLUS, "forged"), 400, "SUBS-007");
        assertCode(purchase(other, "APP_STORE", PRO, proof("tx-6", PLUS, later, Map.of())), 400, "SUBS-007");
        // 거절된 요청은 흔적이 없다
        assertThat(subscriptionRepository.findByUserId(other.getId()).orElseThrow().getPlanTier()).isEqualTo(PlanTier.BASIC);
    }

    @Test
    @DisplayName("스토어로 구독 중이면 웹 결제는 SUBS-009(data.source), 쿠폰 무료 부여 중에는 스토어 구매가 대신한다")
    void sourceRules() {
        assertThat(purchase(user, "PLAY_STORE", PLUS, proof("token-2", PLUS, later, Map.of()))).hasStatusOk();
        assertThat(call(user, "/api/auth/payments/subscribe",
                "{\"planTier\":\"PRO\",\"authKey\":\"auth\",\"idempotencyKey\":\"k-store\"}")).hasStatus(409).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("SUBS-009"))
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("PLAY_STORE"));

        UserSubscription coupon = subscriptionRepository.findByUserId(other.getId()).orElseThrow();
        coupon.activateGrant(PlanTier.PRO, LocalDateTime.now(), later);
        assertThat(purchase(other, "APP_STORE", PLUS, proof("tx-7", PLUS, later.plusDays(1), Map.of()))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("APP_STORE"))
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("PLUS"));
    }

    @Test
    @DisplayName("복원: 유효한 것 중 가장 늦게 끝나는 것으로, 없으면 그대로(200), 유효한 것이 모두 남의 것이면 SUBS-008")
    void restore() {
        assertThat(restore(user, "APP_STORE", List.of(
                Map.of("productId", PLUS, "proof", proof("tx-8", PLUS, later, Map.of())),
                Map.of("productId", PRO, "proof", proof("tx-9", PRO, later.plusDays(5), Map.of())),
                Map.of("productId", PLUS, "proof", proof("tx-10", PLUS, LocalDateTime.now().minusDays(3), Map.of())),
                Map.of("productId", PLUS, "proof", "forged")))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("PRO"))
                .hasPathSatisfying("$.data.source", v -> assertThat(v).isEqualTo("APP_STORE"));
        assertThat(purchaseRepository.findAll()).hasSize(2);   // 만료·위조는 묶지 않는다

        assertThat(restore(other, "APP_STORE", List.of(Map.of("productId", PLUS, "proof", proof("tx-11", PLUS,
                LocalDateTime.now().minusDays(1), Map.of()))))).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.data.planTier", v -> assertThat(v).isEqualTo("BASIC"));
        assertCode(restore(other, "APP_STORE", List.of(Map.of("productId", PLUS, "proof", proof("tx-8", PLUS, later, Map.of())))),
                409, "SUBS-008");
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



    private MvcTestResult purchase(User who, String platform, String productId, String proof) {
        return call(who, "/api/auth/subscriptions/store-purchases",
                objectMapper.writeValueAsString(Map.of("platform", platform, "productId", productId, "proof", proof)));
    }

    private MvcTestResult restore(User who, String platform, List<Map<String, String>> items) {
        return call(who, "/api/auth/subscriptions/store-purchases/restore",
                objectMapper.writeValueAsString(Map.of("platform", platform, "items", items)));
    }

    private MvcTestResult call(User who, String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService
                        .createAccessToken(who.getPublicId(), who.getUserRole(), who.getUserStatus()).value())
                .exchange();
    }


    private static void assertCode(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    private UserSubscription subscription(User who) {
        return subscriptionRepository.findByUserId(who.getId()).orElseThrow();
    }

    private User save(String email) {
        User saved = userRepository.save(User.localUser(email, "e", email));
        subscriptionRepository.save(UserSubscription.createBasic(saved.getId()));
        return saved;
    }
}
