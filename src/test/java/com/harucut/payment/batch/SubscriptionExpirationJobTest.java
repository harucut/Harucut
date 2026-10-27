package com.harucut.payment.batch;

import com.harucut.coupon.entity.Coupon;
import com.harucut.coupon.entity.UserCoupon;
import com.harucut.coupon.enums.UserCouponStatus;
import com.harucut.coupon.repository.CouponRepository;
import com.harucut.coupon.repository.UserCouponRepository;
import com.harucut.payment.entity.BillingKey;
import com.harucut.payment.enums.BillingKeyStatus;
import com.harucut.payment.gateway.PgProvider;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 만료 잡 통합 검증. 갱신 잡 테스트와 같은 규칙: 테스트마다 runDate가 다르다.
 * 외부 의존이 없어 대역 없이 실제 빈으로 돈다 — 실패 주입은 "없는 쿠폰을 예약한 구독"으로 한다.
 * 유예 일수는 test 프로파일에 별도 설정이 없으므로 기본값 3일이 기준이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("구독 만료 배치")
class SubscriptionExpirationJobTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job subscriptionExpirationJob;

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

    // H2가 컨텍스트 간에 공유된다 — 다른 클래스가 남긴 만료 도래 구독이 대상에 끼어들면
    // 스킵 수·행 수 검증이 흔들리므로 결제·구독 테이블을 비우고 시작한다 (갱신 잡 테스트와 동일)
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
    @DisplayName("CANCELED·GRANTED는 기간 종료 즉시, PAST_DUE는 3일 유예 뒤에만 강등된다 — ACTIVE는 안 본다")
    void expiresOnlyEligibleStatuses() throws Exception {
        LocalDate runDate = LocalDate.of(2032, 1, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User canceledDue = saveUser("expire-canceled-due@harucut.com");
        saveCanceled(canceledDue, base);   // 경계: periodEnd == 기준시각도 대상 (<=)
        saveBillingKey(canceledDue, "bk-canceled");
        User canceledFuture = saveUser("expire-canceled-future@harucut.com");
        saveCanceled(canceledFuture, base.plusDays(5));
        User pastDueInGrace = saveUser("expire-pastdue-grace@harucut.com");
        savePastDue(pastDueInGrace, base.minusDays(2));    // 2일째 — 아직 유예
        User pastDueExpired = saveUser("expire-pastdue-done@harucut.com");
        savePastDue(pastDueExpired, base.minusDays(3));    // 3일째 경계 — 유예 끝
        User grantedDue = saveUser("expire-granted-due@harucut.com");
        saveGranted(grantedDue, base);
        User activeDue = saveUser("expire-active-due@harucut.com");
        saveActive(activeDue, base.minusDays(1));

        JobExecution execution = jobOperator.start(subscriptionExpirationJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 강등된 셋: BASIC/EXPIRED/주기 null
        for (User expired : new User[]{canceledDue, pastDueExpired, grantedDue}) {
            UserSubscription subscription = userSubscriptionRepository.findByUserId(expired.getId()).orElseThrow();
            assertThat(subscription.getPlanTier()).isEqualTo(PlanTier.BASIC);
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            assertThat(subscription.getCurrentPeriodEnd()).isNull();
        }
        // 만료와 함께 카드도 해제된다
        assertThat(billingKeyRepository.findAllByUserIdAndStatus(canceledDue.getId(), BillingKeyStatus.ACTIVE))
                .isEmpty();
        // 안 건드린 셋: 기간 미도래 CANCELED, 유예 중 PAST_DUE, 그리고 ACTIVE(갱신 배치의 몫)
        assertThat(userSubscriptionRepository.findByUserId(canceledFuture.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(userSubscriptionRepository.findByUserId(pastDueInGrace.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(userSubscriptionRepository.findByUserId(activeDue.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("스토어 구독: 자동갱신을 끈 것은 기한에 바로, 갱신 중(ACTIVE·PAST_DUE)인데 알림이 없으면 유예(3일) 뒤에 강등 — 출처도 지운다")
    void storeSubscriptions() throws Exception {
        LocalDate runDate = LocalDate.of(2033, 8, 8);
        LocalDateTime base = runDate.atStartOfDay();
        User canceled = saveUser("expire-store-canceled@harucut.com");
        saveStore(canceled, SubscriptionStatus.CANCELED, base);
        User inGrace = saveUser("expire-store-grace@harucut.com");
        saveStore(inGrace, SubscriptionStatus.ACTIVE, base.minusDays(2));
        User lost = saveUser("expire-store-lost@harucut.com");
        saveStore(lost, SubscriptionStatus.ACTIVE, base.minusDays(3));
        User retrying = saveUser("expire-store-retry@harucut.com");
        saveStore(retrying, SubscriptionStatus.PAST_DUE, base.minusDays(3));

        JobExecution execution = jobOperator.start(subscriptionExpirationJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        for (User expired : new User[]{canceled, lost, retrying}) {
            UserSubscription subscription = userSubscriptionRepository.findByUserId(expired.getId()).orElseThrow();
            assertThat(subscription.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            assertThat(subscription.getSource()).isNull();
        }
        assertThat(userSubscriptionRepository.findByUserId(inGrace.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("예약 쿠폰이 있으면 강등 대신 grant로 이어진다 — 카드는 그대로 남는다")
    void reservedCouponConvertsToGrant() throws Exception {
        LocalDate runDate = LocalDate.of(2032, 2, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User user = saveUser("expire-coupon@harucut.com");
        Coupon coupon = couponRepository.save(
                Coupon.create("만료 배치 쿠폰", "BATCH-EXPIRE-1", PlanTier.PRO, null, null));
        UserCoupon userCoupon = userCouponRepository.save(
                UserCoupon.reserved(coupon, user.getId(), base.minusDays(10)));
        UserSubscription subscription = buildCanceled(user, base);
        subscription.reserveGrant(userCoupon.getId());
        userSubscriptionRepository.save(subscription);
        saveBillingKey(user, "bk-keep");

        JobExecution execution = jobOperator.start(subscriptionExpirationJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        UserSubscription granted = userSubscriptionRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(granted.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.GRANTED);
        assertThat(granted.getPlanTier()).isEqualTo(PlanTier.PRO);
        assertThat(granted.getCurrentPeriodStart()).isEqualTo(base);
        assertThat(granted.getCurrentPeriodEnd()).isEqualTo(base.plusMonths(1));
        assertThat(granted.getReservedUserCouponId()).isNull();
        assertThat(userCouponRepository.findById(userCoupon.getId()).orElseThrow().getStatus())
                .isEqualTo(UserCouponStatus.REDEEMED);
        // grant로 전환된 사용자의 카드는 해제하지 않는다 — grant가 끝나는 날 만료 배치가 그때 정리한다
        assertThat(billingKeyRepository.findAllByUserIdAndStatus(user.getId(), BillingKeyStatus.ACTIVE))
                .hasSize(1);
    }

    /*
     * 실패 주입: 없는 쿠폰을 예약한 구독 — grant 개시가 COUPON_NOT_FOUND로 터진다.
     * 대역 없이 실제 경로에서 터뜨리는 게 요점이다. 터진 건은 트랜잭션 롤백으로
     * 원상태(CANCELED + 예약 유지)가 증명되고, 나머지는 처리된다.
     */
    @Test
    @DisplayName("1건이 터져도 나머지는 강등되고 잡은 스킵 1로 완료된다")
    void oneFailureDoesNotStopTheRest() throws Exception {
        LocalDate runDate = LocalDate.of(2032, 3, 10);
        LocalDateTime base = runDate.atStartOfDay();
        User broken = saveUser("expire-broken@harucut.com");
        UserSubscription brokenSub = buildCanceled(broken, base);
        brokenSub.reserveGrant(999_999L);
        userSubscriptionRepository.save(brokenSub);
        User healthy = saveUser("expire-healthy@harucut.com");
        saveCanceled(healthy, base);

        JobExecution execution = jobOperator.start(subscriptionExpirationJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        UserSubscription untouched = userSubscriptionRepository.findByUserId(broken.getId()).orElseThrow();
        assertThat(untouched.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(untouched.getReservedUserCouponId()).isEqualTo(999_999L);
        assertThat(userSubscriptionRepository.findByUserId(healthy.getId()).orElseThrow()
                .getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(execution.getStepExecutions())
                .filteredOn(step -> step.getStepName().equals("expirationStep"))
                .singleElement()
                .satisfies(step -> assertThat(step.getWriteSkipCount()).isEqualTo(1L));
    }

    // ── helpers ──────────────────────────────

    private JobParameters params(LocalDate runDate) {
        return new JobParametersBuilder()
                .addLocalDate(SubscriptionExpirationJobConfig.RUN_DATE, runDate)
                .toJobParameters();
    }

    private User saveUser(String email) {
        return userRepository.save(UserFixtures.localUser(email, "encoded"));
    }

    private void saveBillingKey(User user, String billingKeyValue) {
        billingKeyRepository.save(BillingKey.issue(user.getId(), PgProvider.MOCK, billingKeyValue, "1234-****"));
    }

    private UserSubscription buildCanceled(User user, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, periodEnd.minusMonths(1), periodEnd);
        subscription.cancelAutoRenew();
        return subscription;
    }

    private void saveCanceled(User user, LocalDateTime periodEnd) {
        userSubscriptionRepository.save(buildCanceled(user, periodEnd));
    }

    private void savePastDue(User user, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, periodEnd.minusMonths(1), periodEnd);
        subscription.markPastDue();
        userSubscriptionRepository.save(subscription);
    }

    private void saveGranted(User user, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activateGrant(PlanTier.PLUS, periodEnd.minusMonths(1), periodEnd);
        userSubscriptionRepository.save(subscription);
    }

    private void saveStore(User user, SubscriptionStatus status, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activateStore(SubscriptionSource.PLAY_STORE, PlanTier.PLUS, status, periodEnd.minusMonths(1), periodEnd,
                status != SubscriptionStatus.CANCELED);
        userSubscriptionRepository.save(subscription);
    }

    private void saveActive(User user, LocalDateTime periodEnd) {
        UserSubscription subscription = UserSubscription.createBasic(user.getId());
        subscription.activatePaid(PlanTier.PLUS, periodEnd.minusMonths(1), periodEnd);
        userSubscriptionRepository.save(subscription);
    }
}
