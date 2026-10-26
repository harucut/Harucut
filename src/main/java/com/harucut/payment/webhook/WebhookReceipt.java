package com.harucut.payment.webhook;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 처리한 웹훅 — (제공자, webhook-id) 유니크라 재전송을 한 번만 처리한다
@Entity
@Table(name = "webhook_receipt",
        uniqueConstraints = @UniqueConstraint(name = "uk_webhook_receipt", columnNames = {"provider", "webhook_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "webhook_receipt_id")
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "webhook_id", nullable = false, length = 100)
    private String webhookId;

    @Column(name = "event_type", length = 60)
    private String eventType;

    public WebhookReceipt(LocalDateTime createdAt, String provider, String webhookId, String eventType) {
        this.createdAt = createdAt;
        this.provider = provider;
        this.webhookId = webhookId;
        this.eventType = eventType;
    }
}
