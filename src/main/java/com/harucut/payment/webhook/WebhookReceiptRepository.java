package com.harucut.payment.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, Long> {

    boolean existsByProviderAndWebhookId(String provider, String webhookId);
}
