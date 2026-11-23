package com.harucut.payment.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, Long> {

    // 처음 받은 웹훅이면 1, 이미 기록됐으면 0 — 유니크 위반을 예외로 받지 않는다(받으면 트랜잭션이 롤백 전용이 된다)
    @Modifying
    @Query(value = "insert ignore into webhook_receipt (created_at, provider, webhook_id, event_type) "
            + "values (:createdAt, :provider, :webhookId, :eventType)", nativeQuery = true)
    int recordOnce(@Param("createdAt") LocalDateTime createdAt, @Param("provider") String provider,
                   @Param("webhookId") String webhookId, @Param("eventType") String eventType);
}
