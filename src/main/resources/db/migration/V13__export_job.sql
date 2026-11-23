-- V13 — 행사 사진 묶음 다운로드(ZIP) 작업 (ADR-0008 §4)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `export_job` (
  `export_job_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id` bigint NOT NULL,
  `requested_by_user_id` bigint NOT NULL,
  `status` enum('DONE','EXPIRED','FAILED','PENDING','RUNNING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `photo_count` int NOT NULL,
  `size_bytes` bigint DEFAULT NULL,
  `s3_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `failure_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`export_job_id`),
  UNIQUE KEY `uk_export_job_public_id` (`public_id`),
  KEY `idx_export_job_event_requester` (`event_id`, `requested_by_user_id`),
  KEY `idx_export_job_status` (`status`),
  CONSTRAINT `fk_export_job_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_export_job_requested_by` FOREIGN KEY (`requested_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 접수 시점에 고정한 대상 사진 — 사진이 삭제되면 그 묶음을 무효로 하는 근거
CREATE TABLE `export_job_item` (
  `export_job_item_id` bigint NOT NULL AUTO_INCREMENT,
  `export_job_id` bigint NOT NULL,
  `compose_job_id` bigint NOT NULL,
  `sort_order` int NOT NULL,
  PRIMARY KEY (`export_job_item_id`),
  KEY `idx_export_job_item_job` (`export_job_id`),
  KEY `idx_export_job_item_compose_job` (`compose_job_id`),
  CONSTRAINT `fk_export_job_item_export` FOREIGN KEY (`export_job_id`) REFERENCES `export_job` (`export_job_id`),
  CONSTRAINT `fk_export_job_item_compose_job` FOREIGN KEY (`compose_job_id`) REFERENCES `compose_job` (`compose_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
