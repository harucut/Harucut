package com.harucut.support.mysql;

import com.harucut.common.exception.BusinessException;
import com.harucut.payment.batch.RenewalChargeTransactionService;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.dto.StorePurchaseDtos;
import com.harucut.subscription.store.repository.StorePurchaseRepository;
import com.harucut.subscription.store.service.StoreSubscriptionService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 스토어 구매 묶기 경쟁 (ADR-0011 §2) — 두 계정이 같은 구매를 동시에 등록하면 정확히 한 계정만 묶이고 다른 쪽은 SUBS-008.
 * 없는 키를 잠금 읽기하지 않으므로(빈 구간 gap lock) 교착 없이 유니크가 가른다. 같은 계정의 동시 요청은 구독 행 잠금으로 줄을 선다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("스토어 구매 묶기 경쟁 — 실제 MySQL")
class StorePurchaseRaceMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private StoreSubscriptionService service;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private StorePurchaseRepository purchaseRepository;
    @Autowired private RenewalChargeTransactionService chargeService;
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private BillingKeyRepository billingKeyRepository;

    @Test
    @DisplayName("두 계정이 같은 구매를 동시에 → 하나만 묶이고 다른 쪽 SUBS-008, 같은 계정의 동시 두 번 → 둘 다 성공·한 행")
    void race() throws Exception {
        String proof = "mock:{\"storeKey\":\"race-1\",\"productId\":\"harucut.plus.monthly\",\"expiresAt\":\""
                + LocalDateTime.now().plusDays(30) + "\"}";
        StorePurchaseDtos.PurchaseRequest request = new StorePurchaseDtos.PurchaseRequest(StorePlatform.APP_STORE, "harucut.plus.monthly", proof);
        User a = user("race-a@harucut.com");
        User b = user("race-b@harucut.com");

        List<Object> results = concurrently(() -> service.purchase(a.getPublicId(), request), () -> service.purchase(b.getPublicId(), request));

        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .singleElement().satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(SubscriptionErrorCode.STORE_PURCHASE_OWNED_BY_OTHER));
        assertThat(results).filteredOn(r -> !(r instanceof Throwable)).hasSize(1);
        assertThat(purchaseRepository.findAll()).singleElement();

        String mine = "mock:{\"storeKey\":\"race-2\",\"productId\":\"harucut.plus.monthly\",\"expiresAt\":\""
                + LocalDateTime.now().plusDays(30) + "\"}";
        User c = user("race-c@harucut.com");
        StorePurchaseDtos.PurchaseRequest twice = new StorePurchaseDtos.PurchaseRequest(StorePlatform.APP_STORE, "harucut.plus.monthly", mine);
        List<Object> same = concurrently(() -> service.purchase(c.getPublicId(), twice), () -> service.purchase(c.getPublicId(), twice));
        assertThat(same).noneMatch(Throwable.class::isInstance);
        assertThat(purchaseRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("#43 재리뷰 P1: 스토어 등록과 웹 갱신 청구 직전 확인이 동시에 — 교착 없이, 청구 재료가 나갔으면 주문은 IN_PROGRESS, 아니면 주문이 지워진다(이중 청구 없음)")
    void storeSwitchVersusRenewalCharge() throws Exception {
        for (int round = 0; round < 6; round++) {
            User user = user("switch-" + round + "@harucut.com");
            UserSubscription subscription = subscriptionRepository.findByUserId(user.getId()).orElseThrow();
            subscription.activatePaid(PlanTier.PLUS, LocalDateTime.now().minusMonths(1), LocalDateTime.now().minusHours(1));
            subscriptionRepository.save(subscription);
            billingKeyRepository.save(BillingKey.issue(user.getId(), PgProvider.MOCK, "bk-" + round, "1234-****"));
            Long orderId = paymentOrderRepository.save(PaymentOrder.createRenewal(user.getId(), PlanTier.PLUS, 3900, "renewal:race:" + round)).getId();
            String proof = "mock:{\"storeKey\":\"switch-" + round + "\",\"productId\":\"harucut.plus.monthly\",\"expiresAt\":\""
                    + LocalDateTime.now().plusDays(30) + "\"}";
            StorePurchaseDtos.PurchaseRequest request = new StorePurchaseDtos.PurchaseRequest(StorePlatform.APP_STORE, "harucut.plus.monthly", proof);

            List<Object> results = round % 2 == 0
                    ? concurrently(() -> service.purchase(user.getPublicId(), request), () -> chargeService.markCharging(orderId))
                    : concurrently(() -> chargeService.markCharging(orderId), () -> service.purchase(user.getPublicId(), request));

            assertThat(results).noneMatch(Throwable.class::isInstance);
            Object target = round % 2 == 0 ? results.get(1) : results.get(0);
            if (target != null) {
                assertThat(paymentOrderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
            } else {
                assertThat(paymentOrderRepository.findById(orderId)).isEmpty();
            }
            assertThat(subscriptionRepository.findByUserId(user.getId()).orElseThrow().getSource()).isEqualTo(SubscriptionSource.APP_STORE);
        }
    }

    private static List<Object> concurrently(java.util.function.Supplier<Object> first, java.util.function.Supplier<Object> second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Object> one = CompletableFuture.supplyAsync(() -> run(start, first));
        CompletableFuture<Object> two = CompletableFuture.supplyAsync(() -> run(start, second));
        start.countDown();
        return java.util.Arrays.asList(one.get(), two.get());   // 결과가 null 일 수 있다(청구하지 않음)
    }

    private static Object run(CountDownLatch start, java.util.function.Supplier<Object> work) {
        try {
            start.await();
            return work.get();
        } catch (RuntimeException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    private User user(String email) {
        User saved = userRepository.save(User.localUser(email, "e", email));
        subscriptionRepository.save(UserSubscription.createBasic(saved.getId()));
        return saved;
    }
}
