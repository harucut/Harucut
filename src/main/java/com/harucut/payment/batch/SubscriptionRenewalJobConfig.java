package com.harucut.payment.batch;

import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.AlwaysSkipItemSkipPolicy;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class SubscriptionRenewalJobConfig {

    public static final String JOB_NAME = "subscriptionRenewalJob";
    public static final String RUN_DATE = "runDate";


    @Bean
    public Job subscriptionRenewalJob(JobRepository jobRepository, Step renewalPrepareStep, Step renewalChargeStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(renewalPrepareStep)
                .next(renewalChargeStep)
                .build();
    }

    @Bean
    public Step renewalPrepareStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   JpaCursorItemReader<Long> renewableSubscriptionReader,
                                   ItemWriter<Long> renewalPrepareWriter) {

        return new StepBuilder("renewalPrepareStep", jobRepository)
                .<Long, Long>chunk(1)
                .transactionManager(transactionManager)
                .reader(renewableSubscriptionReader)
                .writer(renewalPrepareWriter)
                .faultTolerant()
                .skipPolicy(new AlwaysSkipItemSkipPolicy())
                .skipListener(new SkipListener<Long, Long>() {
                    @Override
                    public void onSkipInWrite(Long subscriptionId, Throwable t) {
                        log.warn("[갱신 배치] subscriptionId={} 준비 실패 — 건너뛰고 계속", subscriptionId, t);
                    }
                })
                .build();
    }

    @Bean
    public Step renewalChargeStep(JobRepository jobRepository,
                                  JpaCursorItemReader<Long> chargeableOrderReader,
                                  ItemWriter<Long> renewalChargeWriter) {

        // 트랜잭션 매니저를 일부러 안 준다(기본 = Resourceless, 무트랜잭션).
        // 청크가 진짜 트랜잭션을 열면 PG 호출이 그 안에 갇힌다.
        // 커밋 경계는 RenewalChargeTransactionService가 건별로 두 번 관리한다.
        return new StepBuilder("renewalChargeStep", jobRepository)
                .<Long, Long>chunk(1)
                .reader(chargeableOrderReader)
                .writer(renewalChargeWriter)
                .faultTolerant()
                .skipPolicy(new AlwaysSkipItemSkipPolicy())
                .skipListener(new SkipListener<Long, Long>() {
                    @Override
                    public void onSkipInWrite(Long orderId, Throwable t) {
                        log.warn("[갱신 배치] orderId={} 청구 실패 — 건너뛰고 계속", orderId, t);
                    }
                })
                .build();
    }

    @Bean
    @StepScope
    public JpaCursorItemReader<Long> renewableSubscriptionReader(
            EntityManagerFactory entityManagerFactory,
            @Value("#{jobParameters['runDate']}") LocalDate runDate) {
        return new JpaCursorItemReaderBuilder<Long>()
                .name("renewableSubscriptionReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        select s.id from UserSubscription s
                        where s.subscriptionStatus in :statuses
                            and (s.source is null or s.source = :web)
                            and s.currentPeriodEnd is not null
                            and s.currentPeriodEnd <= :baseTime
                        """)
                // 웹 정기결제만 청구한다 — 스토어 구독은 스토어가 갱신하고 카드도 없다(ADR-0011 §2)
                .parameterValues(Map.of(
                        "statuses", List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE),
                        "web", SubscriptionSource.WEB,
                        "baseTime", runDate.atStartOfDay()))
                .build();
    }

    // CREATED만 읽는다 — IN_PROGRESS(결과 불명)는 자동으로 제외된다. 도장의 두 번째 쓸모.
    @Bean
    @StepScope
    public JpaCursorItemReader<Long> chargeableOrderReader(EntityManagerFactory entityManagerFactory) {
        return new JpaCursorItemReaderBuilder<Long>()
                .name("chargeableOrderReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        select o.id from PaymentOrder o
                        where o.orderType = :orderType and o.status = :status
                        """)
                .parameterValues(Map.of(
                        "orderType", OrderType.RENEWAL,
                        "status", OrderStatus.CREATED))
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<Long> renewalPrepareWriter(RenewalPreparationService renewalPreparationService,
                                                 @Value("#{jobParameters['runDate']}") LocalDate runDate) {
        LocalDateTime baseTime = runDate.atStartOfDay();
        return chunk -> chunk.getItems().forEach(id -> renewalPreparationService.prepare(id, baseTime));
    }

    @Bean
    @StepScope
    public ItemWriter<Long> renewalChargeWriter(RenewalChargeService renewalChargeService,
                                                @Value("#{jobParameters['runDate']}") LocalDate runDate) {
        LocalDateTime baseTime = runDate.atStartOfDay();
        return chunk -> chunk.getItems().forEach(id -> renewalChargeService.charge(id, baseTime));
    }
}
