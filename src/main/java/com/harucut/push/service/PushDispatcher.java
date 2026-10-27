package com.harucut.push.service;

import com.harucut.push.entity.DeviceToken;
import com.harucut.push.repository.DeviceTokenRepository;
import com.harucut.push.sender.PushSender;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 커밋된 푸시 요청을 전용 스레드에서 보낸다. 요청 스레드(합성 통지 소비자·배치)는 외부 호출을 기다리지 않는다 —
 * 소비자의 가시성 타임아웃(30초)이 처리 시간 상한이라 발송 지연이 통지 재배달로 번지면 안 된다.
 *
 * <p>최대 한 번 발송이다. 큐가 차거나 발송이 실패하면 버리고 로그만 남긴다(알림은 놓쳐도 상태는 화면에서 보인다).
 * Executor 를 빈으로 두지 않는다 — 빈이 생기면 Boot 의 기본 applicationTaskExecutor 가 물러난다(ExportWorker 와 같은 선택).
 */
@Slf4j
@Component
public class PushDispatcher {

    private static final int QUEUE_CAPACITY = 1_000;

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;

    public PushDispatcher(DeviceTokenRepository deviceTokenRepository, PushSender pushSender,
                          PlatformTransactionManager transactionManager) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.pushSender = pushSender;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> new Thread(runnable, "push-dispatch"),
                (runnable, pool) -> log.warn("[푸시] 발송 대기열이 가득 차 버린다"));
    }

    // 트랜잭션 밖에서 발행돼도 보낸다(fallbackExecution) — 롤백이 없는 호출이라 기다릴 커밋이 없다
    @TransactionalEventListener(fallbackExecution = true)
    public void on(PushRequestedEvent event) {
        executor.execute(() -> dispatch(event));
    }

    void dispatch(PushRequestedEvent event) {
        try {
            List<String> tokens = deviceTokenRepository.findTokensByUserId(event.userId());
            if (tokens.isEmpty()) {
                return;
            }
            Set<String> unregistered = pushSender.send(tokens, event.message());
            if (!unregistered.isEmpty()) {
                transactionTemplate.executeWithoutResult(status -> deviceTokenRepository.deleteByTokens(unregistered));
                log.info("[푸시] 받지 않는 기기 정리 {}", unregistered.stream().map(DeviceToken::mask).toList());
            }
        } catch (RuntimeException e) {
            log.warn("[푸시] 발송 처리 실패 userId={} type={}", event.userId(), event.message().type(), e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
