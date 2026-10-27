package com.harucut.payment.batch;

import com.harucut.coupon.entity.Coupon;
import com.harucut.coupon.entity.UserCoupon;
import com.harucut.coupon.enums.UserCouponStatus;
import com.harucut.coupon.repository.CouponRepository;
import com.harucut.coupon.repository.UserCouponRepository;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.payment.enums.PaymentStatus;
import com.harucut.payment.gateway.PaymentGateway;
import com.harucut.payment.gateway.PgProvider;
import com.harucut.payment.gateway.dto.BillingChargeCommand;
import com.harucut.payment.gateway.dto.PaymentResult;
import com.harucut.payment.repository.BillingKeyRepository;
import com.harucut.payment.repository.PaymentOrderRepository;
import com.harucut.payment.repository.PaymentRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/*
 * 갱신 잡 통합 검증. 탈퇴 잡 테스트와 같은 이유로 테스트마다 runDate가 다르다
 * (JobInstance = 잡 이름 + 식별 파라미터).
 * PaymentGateway를 대역으로 바꿔 성공·실패·예외를 주입한다 — 청구 명령의 빌링키 값으로 사용자를 구분한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("구독 갱신 배치")
class SubscriptionRenewalJobTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job subscriptionRenewalJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BillingKeyRepository billingKeyRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @MockitoBean
    private PaymentGateway paymentGateway;

    /*
     * H2가 테스트 컨텍스트들 사이에 공유된다 — 다른 클래스가 남긴 "만료 도래" 구독·빌링키가
     * 이 잡의 대상으로 끼어들면 주문 수·스킵 수 검증이 흔들린다. 결제·구독 테이블을 비우고 시작한다.
     */
    @BeforeEach
    void cleanSlate() {
        paymentRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        billingKeyRepository.deleteAll();
        userCouponRepository.deleteAll();
        couponRepository.deleteAll();
        userSubscriptionRepository.deleteAll();
    }

    @Test
    @DisplayName("만료 도래 ACTIVE만 갱신된다 — 미래·BASIC·CANCELED는 건드리지 않는다")
    void renewsOnlyDueActive() throws Exception {
        LocalDate runDate = LocalDate.of(2031, 1, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User due = saveUser("renewal-due@harucut.com");
        // 경계 확인: periodEnd == 기준시각(자정)도 대상이다 (<=)
        UserSubscription dueSub = saveActivePlus(due, base);
        saveBillingKey(due, "bk-due");
        User future = saveUser("renewal-future@harucut.com");
        saveActivePlus(future, base.plusDays(5));
        User basic = saveUser("renewal-basic@harucut.com");
        userSubscriptionRepository.save(UserSubscription.createBasic(basic.getId()));
        User canceled = saveUser("renewal-canceled@harucut.com");
        UserSubscription canceledSub = UserSubscription.createBasic(canceled.getId());
        canceledSub.activatePaid(PlanTier.PLUS, base.minusMonths(1), base.minusDays(1));
        canceledSub.cancelAutoRenew();
        userSubscriptionRepository.save(canceledSub);
        given(paymentGateway.charge(any(BillingChargeCommand.class)))
                .willReturn(PaymentResult.success("tx-1", base.plusHours(2)));

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        UserSubscription renewed = userSubscriptionRepository.findByUserId(due.getId()).orElseThrow();
        assertThat(renewed.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewed.getCurrentPeriodStart()).isEqualTo(base);
        assertThat(renewed.getCurrentPeriodEnd()).isEqualTo(base.plusMonths(1));

        PaymentOrder order = paymentOrderRepository
                .findByIdempotencyKey("renewal:" + dueSub.getId() + ":20310110").orElseThrow();
        assertThat(order.getOrderType()).isEqualTo(OrderType.RENEWAL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getAmount()).isEqualTo(3900);

        // 주문은 due 사용자 것 하나뿐 — 나머지 셋은 대상조차 아니었다
        assertThat(paymentOrderRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getUserId()).isEqualTo(due.getId()));
        assertThat(userSubscriptionRepository.findByUserId(future.getId()).orElseThrow()
                .getCurrentPeriodEnd()).isEqualTo(base.plusDays(5));
        assertThat(userSubscriptionRepository.findByUserId(canceled.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("스토어 구독은 청구하지 않는다 — 기한이 지나도 주문도 PAST_DUE 도 없다(스토어가 갱신한다, ADR-0011 §2)")
    void skipsStoreSubscriptions() throws Exception {
        LocalDate runDate = LocalDate.of(2033, 7, 7);
        LocalDateTime base = runDate.atStartOfDay();
        User store = saveUser("renewal-store@harucut.com");
        UserSubscription subscription = UserSubscription.createBasic(store.getId());
        subscription.activateStore(SubscriptionSource.APP_STORE, PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                base.minusMonths(1), base.minusDays(1), true);
        userSubscriptionRepository.save(subscription);

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(paymentOrderRepository.findAll()).isEmpty();
        assertThat(userSubscriptionRepository.findByUserId(store.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    // 유예 3일 동안 매일 재청구한다는 설계 결정을 고정하는 테스트
    @Test
    @DisplayName("PAST_DUE도 대상이다 — 어제 실패한 구독이 오늘 성공하면 ACTIVE로 살아난다")
    void pastDueIsRetried() throws Exception {
        LocalDate runDate = LocalDate.of(2031, 2, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User user = saveUser("renewal-pastdue@harucut.com");
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, base.minusMonths(1).minusDays(1), base.minusDays(1));
        subscription.markPastDue();
        userSubscriptionRepository.save(subscription);
        saveBillingKey(user, "bk-retry");
        given(paymentGateway.charge(any(BillingChargeCommand.class)))
                .willReturn(PaymentResult.success("tx-retry", base.plusHours(2)));

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        UserSubscription renewed = userSubscriptionRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(renewed.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewed.getCurrentPeriodEnd()).isEqualTo(base.plusMonths(1));
    }

    @Test
    @DisplayName("청구 실패면 주문 FAILED·결제 FAILED 행이 남고 구독은 PAST_DUE다")
    void chargeFailureLeavesTrail() throws Exception {
        LocalDate runDate = LocalDate.of(2031, 3, 10);
        LocalDateTime base = runDate.atStartOfDay();
        LocalDateTime originalPeriodEnd = base.minusDays(1);
        User user = saveUser("renewal-fail@harucut.com");
        saveActivePlus(user, originalPeriodEnd);
        saveBillingKey(user, "bk-fail");
        given(paymentGateway.charge(any(BillingChargeCommand.class)))
                .willReturn(PaymentResult.failure("CARD_DECLINED", "한도 초과"));

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(paymentOrderRepository.findAll()).singleElement()
                .satisfies(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED));
        // 트랜잭션 분리 검증의 갱신판 — 청구가 실패해도 이력은 커밋돼 남는다
        assertThat(paymentRepository.findAll()).singleElement().satisfies(payment -> {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");
        });
        UserSubscription subscription = userSubscriptionRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(subscription.getCurrentPeriodEnd()).isEqualTo(originalPeriodEnd);
    }

    @Test
    @DisplayName("예약 쿠폰이 있으면 청구 대신 grant로 전환된다 — PG는 불리지 않는다")
    void reservedCouponConvertsToGrant() throws Exception {
        LocalDate runDate = LocalDate.of(2031, 4, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User user = saveUser("renewal-coupon@harucut.com");
        Coupon coupon = couponRepository.save(
                Coupon.create("갱신 배치 쿠폰", "BATCH-RENEW-1", PlanTier.PRO, null, null));
        UserCoupon userCoupon = userCouponRepository.save(
                UserCoupon.reserved(coupon, user.getId(), base.minusDays(3)));
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, base.minusMonths(1), base);
        subscription.reserveGrant(userCoupon.getId());
        userSubscriptionRepository.save(subscription);
        saveBillingKey(user, "bk-unused");   // 카드가 있어도 쿠폰이 우선이다

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        UserSubscription granted = userSubscriptionRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(granted.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.GRANTED);
        assertThat(granted.getPlanTier()).isEqualTo(PlanTier.PRO);
        assertThat(granted.getCurrentPeriodStart()).isEqualTo(base);
        assertThat(granted.getCurrentPeriodEnd()).isEqualTo(base.plusMonths(1));
        assertThat(granted.getReservedUserCouponId()).isNull();
        assertThat(userCouponRepository.findById(userCoupon.getId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.REDEEMED);
        assertThat(paymentOrderRepository.findAll()).isEmpty();
        then(paymentGateway).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("한 명의 청구가 터져도 나머지는 갱신되고 잡은 스킵 1로 완료된다")
    void oneChargeFailureDoesNotStopTheRest() throws Exception {
        LocalDate runDate = LocalDate.of(2031, 5, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User boom = saveUser("renewal-boom@harucut.com");
        UserSubscription boomSub = saveActivePlus(boom, base.minusDays(1));
        saveBillingKey(boom, "bk-boom");
        User healthy = saveUser("renewal-healthy@harucut.com");
        saveActivePlus(healthy, base.minusDays(1));
        saveBillingKey(healthy, "bk-ok");
        given(paymentGateway.charge(any(BillingChargeCommand.class))).willAnswer(invocation -> {
            BillingChargeCommand command = invocation.getArgument(0);
            if (command.billingKeyValue().equals("bk-boom")) {
                throw new IllegalStateException("PG 응답 없음");
            }
            return PaymentResult.success("tx-ok", base.plusHours(2));
        });

        JobExecution execution = jobOperator.start(subscriptionRenewalJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(userSubscriptionRepository.findByUserId(healthy.getId()).orElseThrow()
                .getCurrentPeriodEnd()).isEqualTo(base.plusMonths(1));
        // 터진 쪽: 도장(IN_PROGRESS)은 남고 결과는 미기록 — 내일의 "미확정 주문" 가드가 이 행을 본다
        assertThat(paymentOrderRepository
                .findByIdempotencyKey("renewal:" + boomSub.getId() + ":20310510").orElseThrow()
                .getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(userSubscriptionRepository.findByUserId(boom.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(paymentRepository.findAll()).singleElement()
                .satisfies(payment -> assertThat(payment.getPgTransactionId()).isEqualTo("tx-ok"));
        /*
         * 스킵 카운트는 0이 정상이다: 예외가 나면 Spring Batch가 청크를 롤백하고 항목을 다시 쓰는데(scan),
         * 재시도에서 markCharging이 IN_PROGRESS 도장을 보고 null을 돌려줘 "성공"으로 끝난다.
         * 그래서 실패의 흔적은 롤백 1회로 남고, 같은 주문이 PG로 두 번 나가지 않는다 —
         * PG 호출 수(사용자당 정확히 1회)가 그 증거다.
         */
        assertThat(execution.getStepExecutions())
                .filteredOn(step -> step.getStepName().equals("renewalChargeStep"))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getReadCount()).isEqualTo(2L);
                    assertThat(step.getRollbackCount()).isEqualTo(1L);
                });
        then(paymentGateway).should(times(2)).charge(any(BillingChargeCommand.class));
    }

    // ── helpers ──────────────────────────────

    private JobParameters params(LocalDate runDate) {
        return new JobParametersBuilder()
                .addLocalDate(SubscriptionRenewalJobConfig.RUN_DATE, runDate)
                .toJobParameters();
    }

    private User saveUser(String email) {
        return userRepository.save(UserFixtures.localUser(email, "encoded"));
    }

    private UserSubscription saveActivePlus(User user, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, periodEnd.minusMonths(1), periodEnd);
        return userSubscriptionRepository.save(subscription);
    }

    private void saveBillingKey(User user, String billingKeyValue) {
        billingKeyRepository.save(BillingKey.issue(user.getId(), PgProvider.MOCK, billingKeyValue, "1234-****"));
    }
}
