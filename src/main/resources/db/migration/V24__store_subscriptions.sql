-- V24 — 구독 출처와 스토어 구매 (ADR-0011 §2, P4-B)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `user_subscription`
  -- 유료 등급의 출처. BASIC·만료면 NULL. 웹(PortOne 정기결제)만 우리 갱신 배치가 청구한다
  ADD COLUMN `source` enum('WEB','APP_STORE','PLAY_STORE','COUPON') COLLATE utf8mb4_unicode_ci DEFAULT NULL;

-- 지금까지의 유료 구독은 모두 웹 결제, 무료 부여(GRANTED)는 쿠폰
UPDATE `user_subscription` SET `source` = 'WEB'
  WHERE `plan_tier` <> 'BASIC' AND `subscription_status` IN ('ACTIVE','CANCELED','PAST_DUE');
UPDATE `user_subscription` SET `source` = 'COUPON' WHERE `subscription_status` = 'GRANTED';

-- 스토어 구매 — 한 구매(애플 원거래·구글 구매 토큰)는 한 계정에만 묶인다
CREATE TABLE `store_purchase` (
  `store_purchase_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `platform` enum('APP_STORE','PLAY_STORE') COLLATE utf8mb4_unicode_ci NOT NULL,
  -- 애플 originalTransactionId · 구글 purchaseToken(재검증에 쓴다)
  `store_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_key_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `product_id` varchar(150) COLLATE utf8mb4_unicode_ci NOT NULL,
  `plan_tier` enum('BASIC','PLUS','PRO') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','CANCELED','PAST_DUE','EXPIRED','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `auto_renew` bit(1) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `environment` enum('SANDBOX','PRODUCTION') COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_verified_at` datetime(6) NOT NULL,
  PRIMARY KEY (`store_purchase_id`),
  UNIQUE KEY `uk_store_purchase_key` (`platform`, `store_key_hash`),
  KEY `idx_store_purchase_user` (`user_id`),
  CONSTRAINT `fk_store_purchase_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
