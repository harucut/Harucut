-- V16 — PortOne V2 웹훅 수신 기록과 계약 결제 확인 (ADR-0008 §8)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

-- 같은 webhook-id 는 한 번만 처리한다(제공자의 재전송·중복 전달)
CREATE TABLE `webhook_receipt` (
  `webhook_receipt_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `provider` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `webhook_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_type` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`webhook_receipt_id`),
  UNIQUE KEY `uk_webhook_receipt` (`provider`, `webhook_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `contract`
  -- 결제 증빙(PORTONE paymentId)을 제공자 API 로 조회해 금액·통화·상점·상태가 맞음을 확인한 시각
  ADD COLUMN `payment_verified_at` datetime(6) DEFAULT NULL;
