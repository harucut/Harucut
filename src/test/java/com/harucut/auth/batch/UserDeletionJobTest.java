package com.harucut.auth.batch;

import com.harucut.auth.service.RefreshTokenService;
import com.harucut.coupon.entity.Coupon;
import com.harucut.coupon.entity.UserCoupon;
import com.harucut.coupon.repository.CouponRepository;
import com.harucut.coupon.repository.UserCouponRepository;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.support.UserFixtures;
import com.harucut.terms.handler.TermsAgreementDeletionHandler;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/*
 * 탈퇴 하드삭제 잡 통합 검증.
 * - 테스트마다 runDate를 다르게 준다: JobInstance는 (잡 이름 + 식별 파라미터)로 유일해서
 *   같은 날짜를 두 테스트가 쓰면 두 번째가 "이미 완료됨"으로 거절당한다 (그 자체가 세 번째 테스트의 주제다)
 * - RefreshTokenService는 Redis가 필요하므로 대역. terms 핸들러는 실패 주입용 대역
 *   (대역은 기본이 no-op이라 다른 테스트에 영향 없음 — terms 데이터는 여기서 검증하지 않는다)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("탈퇴 하드삭제 배치")
class UserDeletionJobTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job userDeletionJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private TermsAgreementDeletionHandler termsAgreementDeletionHandler;

    @Test
    @DisplayName("유예가 지난 사용자만 도메인 데이터가 지워지고 익명화된다")
    void deletesOnlyExpiredRequests() throws Exception {
        LocalDate runDate = LocalDate.of(2030, 1, 10);   // threshold = 1/3 00:00
        User expired = newDeleteRequestedUser("batch-expired@harucut.com", LocalDateTime.of(2030, 1, 2, 0, 0));
        User waiting = newDeleteRequestedUser("batch-waiting@harucut.com", LocalDateTime.of(2030, 1, 5, 0, 0));
        User active = userRepository.save(UserFixtures.localUser("batch-active@harucut.com", "encoded"));
        userSubscriptionRepository.save(UserSubscription.createBasic(expired.getId()));
        Coupon coupon = couponRepository.save(
                Coupon.create("배치 테스트", "BATCH-DEL-1", PlanTier.PRO, null, null));
        userCouponRepository.save(UserCoupon.redeemed(coupon, expired.getId(), LocalDateTime.of(2030, 1, 1, 0, 0)));

        JobExecution execution = jobOperator.start(userDeletionJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        User anonymized = userRepository.findById(expired.getId()).orElseThrow();
        assertThat(anonymized.getUserStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(anonymized.getEmail()).isEqualTo("deleted_" + expired.getId() + "@harucut.local");
        assertThat(userSubscriptionRepository.findByUserId(expired.getId())).isEmpty();
        assertThat(userCouponRepository.findAll())
                .noneMatch(row -> row.getUserId().equals(expired.getId()));
        then(refreshTokenService).should().revokeAll(expired.getPublicId());

        // 6일밖에 안 된 요청과 멀쩡한 사용자는 그대로다
        assertThat(userRepository.findById(waiting.getId()).orElseThrow().getUserStatus())
                .isEqualTo(UserStatus.DELETED_REQUESTED);
        assertThat(userRepository.findById(active.getId()).orElseThrow().getUserStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /*
     * 핸들러 하나가 특정 사용자에서 터지게 만든다.
     * 그 사용자만 롤백(익명화 안 됨)되고, 나머지는 처리되고, 잡은 스킵 1로 완료 —
     * "1건 실패가 전체를 막지 않는다"의 Spring Batch 판 증명이다.
     */
    @Test
    @DisplayName("1건이 실패해도 나머지는 처리되고 잡은 스킵 1로 완료된다")
    void oneFailureDoesNotStopTheRest() throws Exception {
        LocalDate runDate = LocalDate.of(2030, 2, 10);
        User failing = newDeleteRequestedUser("batch-fail@harucut.com", LocalDateTime.of(2030, 2, 1, 0, 0));
        User healthy = newDeleteRequestedUser("batch-ok@harucut.com", LocalDateTime.of(2030, 2, 1, 0, 0));
        willThrow(new IllegalStateException("핸들러 고장"))
                .given(termsAgreementDeletionHandler).handleUserDeletion(failing.getId());

        JobExecution execution = jobOperator.start(userDeletionJob, params(runDate));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(userRepository.findById(failing.getId()).orElseThrow().getUserStatus())
                .isEqualTo(UserStatus.DELETED_REQUESTED);   // 청크 롤백 — 익명화가 안 남았다
        assertThat(userRepository.findById(healthy.getId()).orElseThrow().getUserStatus())
                .isEqualTo(UserStatus.DELETED);
        assertThat(execution.getStepExecutions()).singleElement()
                .satisfies(step -> assertThat(step.getWriteSkipCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("같은 날짜로 두 번 기동하면 두 번째는 거절된다 — 메타테이블이 중복 실행을 막는다")
    void rejectsDuplicateRunDate() throws Exception {
        LocalDate runDate = LocalDate.of(2030, 3, 10);
        jobOperator.start(userDeletionJob, params(runDate));

        assertThatThrownBy(() -> jobOperator.start(userDeletionJob, params(runDate)))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    private JobParameters params(LocalDate runDate) {
        return new JobParametersBuilder()
                .addLocalDate(UserDeletionJobConfig.RUN_DATE, runDate)
                .toJobParameters();
    }

    private User newDeleteRequestedUser(String email, LocalDateTime requestedAt) {
        User user = UserFixtures.localUser(email, "encoded");
        user.deleteRequested(requestedAt);
        return userRepository.save(user);
    }
}
