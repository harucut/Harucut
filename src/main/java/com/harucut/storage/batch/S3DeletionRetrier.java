package com.harucut.storage.batch;

import com.harucut.storage.entity.S3Deletion;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.S3DeletionAttempt;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 커밋 뒤 한 번에 못 지운 S3 객체를 다시 지운다(V18 s3_deletion). 차례가 된 행을 100개씩, 상한 아래만.
 * 상한을 넘어 남은 행은 지우지 않고 건수를 로그·지표(harucut.s3.deletion.abandoned)로 낸다 — 운영자가 원인을 보고 처리한다.
 * 여러 인스턴스가 같은 행을 동시에 집어도 S3 삭제는 멱등이라 결과가 같다.
 */
@Slf4j
@Component
public class S3DeletionRetrier {

    private final S3DeletionRepository repository;
    private final S3DeletionAttempt deletionAttempt;
    private final Clock clock;
    // 게이지는 스크레이프마다 읽힌다 — COUNT 쿼리는 배치 주기에만 돌리고 값만 들고 있는다(-1 = 아직 안 셈)
    private final AtomicLong pending = new AtomicLong(-1);
    private final AtomicLong abandoned = new AtomicLong(-1);

    public S3DeletionRetrier(S3DeletionRepository repository, S3DeletionAttempt deletionAttempt, Clock clock,
                             MeterRegistry registry) {
        this.repository = repository;
        this.deletionAttempt = deletionAttempt;
        this.clock = clock;
        Gauge.builder("harucut.s3.deletion.pending", pending, AtomicLong::get)
                .description("아직 지우지 못해 재시도를 기다리는 S3 객체 수").register(registry);
        Gauge.builder("harucut.s3.deletion.abandoned", abandoned, AtomicLong::get)
                .description("재시도 상한을 넘어 남겨 둔 S3 객체 수 — 0 이 아니면 원인을 본다").register(registry);
    }

    // 첫 실행도 한 주기 뒤 — 기동 직후 커밋 뒤 첫 시도와 겹치지 않게
    @Scheduled(fixedDelayString = "${storage.deletion.retry-interval:5m}", initialDelayString = "${storage.deletion.retry-interval:5m}")
    public void run() {
        Result result = retryDue();
        if (result.abandoned() > 0) {
            log.warn("[S3 삭제] 재시도 상한을 넘어 남은 삭제 {}건 — s3_deletion 의 last_error 를 확인할 것", result.abandoned());
        }
    }

    public Result retryDue() {
        int max = deletionAttempt.maxAttempts();
        List<S3Deletion> due = repository.findTop100ByAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByIdAsc(max,
                LocalDateTime.now(clock));
        int deleted = 0;
        for (S3Deletion deletion : due) {
            if (deletionAttempt.attempt(deletion.getS3Key(), deletion.getAttempts())) {
                deleted++;
            }
        }
        pending.set(repository.countByAttemptsLessThan(max));
        abandoned.set(repository.countByAttemptsGreaterThanEqual(max));
        return new Result(due.size(), deleted, pending.get(), abandoned.get());
    }

    public record Result(int tried, int deleted, long pending, long abandoned) {
    }
}
