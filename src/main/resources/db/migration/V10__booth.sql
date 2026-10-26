-- V10 — 부스 기기·연결 코드 (ADR-0007 §3·§7)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `booth_device` (
  `booth_device_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `event_id` bigint NOT NULL,
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','PENDING_PAIRING','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  -- 기기 토큰의 SHA-256. 연결 전·회수 후에는 NULL. 원문은 저장하지 않는다
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 연결(redeem)마다 오른다 — 재연결하면 이전 토큰이 무효가 된 것을 기록으로 남긴다
  `credential_version` int NOT NULL,
  `last_seen_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`booth_device_id`),
  UNIQUE KEY `uk_booth_device_public_id` (`public_id`),
  UNIQUE KEY `uk_booth_device_token_hash` (`token_hash`),
  KEY `idx_booth_device_event_id` (`event_id`),
  CONSTRAINT `fk_booth_device_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_booth_device_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `booth_pairing` (
  `booth_pairing_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `booth_device_id` bigint NOT NULL,
  -- 연결 코드 XXXX-XXXX 의 앞 4자 — 어느 연결 거래인지 찾는 값(비밀 아님). 실패 횟수를 이 거래에 센다
  `lookup` char(4) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- 뒤 4자의 SHA-256. 코드 원문은 저장하지 않는다
  `secret_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `failed_attempts` int NOT NULL,
  `status` enum('CLOSED','OPEN','REDEEMED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`booth_pairing_id`),
  KEY `idx_booth_pairing_lookup` (`lookup`),
  KEY `idx_booth_pairing_booth_device_id` (`booth_device_id`),
  CONSTRAINT `fk_booth_pairing_booth_device` FOREIGN KEY (`booth_device_id`) REFERENCES `booth_device` (`booth_device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V9 에서 미뤄 둔 FK
ALTER TABLE `participant`
  ADD CONSTRAINT `fk_participant_booth_device` FOREIGN KEY (`booth_device_id`) REFERENCES `booth_device` (`booth_device_id`);

ALTER TABLE `capture_run`
  ADD CONSTRAINT `fk_capture_run_booth_device` FOREIGN KEY (`booth_device_id`) REFERENCES `booth_device` (`booth_device_id`),
  ADD CONSTRAINT `fk_capture_run_active_booth_device` FOREIGN KEY (`active_booth_device_id`) REFERENCES `booth_device` (`booth_device_id`);
