-- V28 — 푸시 기기 등록·한 번만 보낼 푸시 기록 (app-shell 푸시)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `device_token` (
  `device_token_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  -- Expo 푸시 토큰. 한 토큰은 한 계정에만 — 다른 계정이 등록하면 옮겨 간다(upsert)
  `token` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `platform` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- 계정당 10대 상한을 넘으면 이 값이 가장 오래된 기기부터 지운다
  `last_registered_at` datetime(6) NOT NULL,
  PRIMARY KEY (`device_token_id`),
  UNIQUE KEY `uk_device_token_token` (`token`),
  KEY `idx_device_token_user_id` (`user_id`, `last_registered_at`),
  CONSTRAINT `fk_device_token_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `push_dedup` (
  `push_dedup_id` bigint NOT NULL AUTO_INCREMENT,
  -- 예: SUBSCRIPTION_EXPIRING:{구독 id}:{기한}
  `dedup_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`push_dedup_id`),
  UNIQUE KEY `uk_push_dedup_key` (`dedup_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
