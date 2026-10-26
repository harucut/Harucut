-- V14 — 문의·계약·이용권·사용량 예약과 원장 (ADR-0008 §6)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `inquiry` (
  `inquiry_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `contact_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `phone` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `organizer_type` enum('BUSINESS','PERSONAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `channels` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expected_date` date DEFAULT NULL,
  `expected_count` int DEFAULT NULL,
  `message` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('CONTACTED','LOST','NEW','QUOTED','WON') COLLATE utf8mb4_unicode_ci NOT NULL,
  `assignee_note` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`inquiry_id`),
  UNIQUE KEY `uk_inquiry_public_id` (`public_id`),
  KEY `idx_inquiry_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `contract` (
  `contract_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `contractor_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope` enum('EVENT','WORKSPACE') COLLATE utf8mb4_unicode_ci NOT NULL,
  -- EVENT 범위일 때 적용 행사 내부 id 의 쉼표 목록
  `event_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `amount` bigint DEFAULT NULL,
  `currency` char(3) COLLATE utf8mb4_unicode_ci NOT NULL,
  `starts_at` datetime(6) NOT NULL,
  `ends_at` datetime(6) NOT NULL,
  `evidence_kind` enum('BANK_TRANSFER','OTHER','PORTONE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `evidence_reference` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- ACTIVE 가 될 때 만들 이용권 조건
  `compose_quota` int NOT NULL,
  `retention_days` int DEFAULT NULL,
  `max_booth_devices` int DEFAULT NULL,
  `max_live_events` int DEFAULT NULL,
  `features` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','CANCELLED','DRAFT','ENDED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by_user_id` bigint NOT NULL,
  PRIMARY KEY (`contract_id`),
  UNIQUE KEY `uk_contract_public_id` (`public_id`),
  -- 결제 증빙 하나는 계약 하나에만 — 웹훅 대조가 한 계약을 찾는다(NULL 은 여러 개 허용)
  UNIQUE KEY `uk_contract_evidence` (`evidence_kind`, `evidence_reference`),
  KEY `idx_contract_workspace_id` (`workspace_id`),
  CONSTRAINT `fk_contract_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_contract_created_by` FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `entitlement` (
  `entitlement_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope` enum('EVENT','WORKSPACE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `event_id` bigint DEFAULT NULL,
  `source` enum('CONTRACT','MANUAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `contract_id` bigint DEFAULT NULL,
  `compose_quota` int NOT NULL,
  -- 성공 확정 수와 진행 중 예약 수 — used + reserved <= compose_quota 를 조건부 UPDATE 로 지킨다
  `used` int NOT NULL,
  `reserved` int NOT NULL,
  `retention_days` int DEFAULT NULL,
  `max_booth_devices` int DEFAULT NULL,
  `max_live_events` int DEFAULT NULL,
  `features` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `valid_from` datetime(6) NOT NULL,
  `valid_to` datetime(6) NOT NULL,
  `status` enum('ACTIVE','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `granted_by_user_id` bigint DEFAULT NULL,
  `grantor_note` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `revoke_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`entitlement_id`),
  UNIQUE KEY `uk_entitlement_public_id` (`public_id`),
  KEY `idx_entitlement_workspace` (`workspace_id`, `status`),
  KEY `idx_entitlement_event` (`event_id`, `status`),
  CONSTRAINT `fk_entitlement_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_entitlement_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_entitlement_contract` FOREIGN KEY (`contract_id`) REFERENCES `contract` (`contract_id`),
  CONSTRAINT `fk_entitlement_granted_by` FOREIGN KEY (`granted_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 합성 작업 하나에 예약 하나 — RESERVED 에서 COMMITTED·RELEASED 중 하나로만 한 번 바뀐다
CREATE TABLE `usage_reservation` (
  `usage_reservation_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `compose_job_id` bigint NOT NULL,
  `entitlement_id` bigint NOT NULL,
  `status` enum('COMMITTED','RELEASED','RESERVED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`usage_reservation_id`),
  UNIQUE KEY `uk_usage_reservation_job` (`compose_job_id`),
  KEY `idx_usage_reservation_entitlement` (`entitlement_id`, `status`),
  CONSTRAINT `fk_usage_reservation_job` FOREIGN KEY (`compose_job_id`) REFERENCES `compose_job` (`compose_job_id`),
  CONSTRAINT `fk_usage_reservation_entitlement` FOREIGN KEY (`entitlement_id`) REFERENCES `entitlement` (`entitlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 감사용 이력 — 예약·확정·반환 한 줄씩
CREATE TABLE `usage_ledger` (
  `usage_ledger_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `entitlement_id` bigint NOT NULL,
  `compose_job_id` bigint NOT NULL,
  `kind` enum('COMMIT','RELEASE','RESERVE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `quantity` int NOT NULL,
  PRIMARY KEY (`usage_ledger_id`),
  UNIQUE KEY `uk_usage_ledger_job_kind` (`compose_job_id`, `kind`),
  KEY `idx_usage_ledger_entitlement` (`entitlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
