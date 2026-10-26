-- V8 — 행사와 경험 발행본 (ADR-0007 §3·§4)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `slug` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ARCHIVED','CAPTURE_CLOSED','DRAFT','LIVE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `paused` bit(1) NOT NULL,
  `capture_starts_at` datetime(6) DEFAULT NULL,
  `capture_ends_at` datetime(6) DEFAULT NULL,
  `timezone` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `byod_enabled` bit(1) NOT NULL,
  `booth_enabled` bit(1) NOT NULL,
  `result_retention_days` int NOT NULL,
  -- 허용 레이아웃·프레임·동의 항목·테마 초안(JSON). 게시하면 event_experience_version 으로 굳는다
  `settings_draft` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `settings_version` int NOT NULL,
  `current_experience_version_id` bigint DEFAULT NULL,
  `created_by_user_id` bigint NOT NULL,
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_event_public_id` (`public_id`),
  UNIQUE KEY `uk_event_slug` (`slug`),
  KEY `idx_event_workspace_id` (`workspace_id`),
  CONSTRAINT `fk_event_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_event_created_by` FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `event_experience_version` (
  `event_experience_version_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `settings_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `published_by_user_id` bigint NOT NULL,
  PRIMARY KEY (`event_experience_version_id`),
  UNIQUE KEY `uk_event_experience_version_no` (`event_id`,`version_no`),
  CONSTRAINT `fk_event_experience_version_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_event_experience_version_published_by` FOREIGN KEY (`published_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `event`
  ADD CONSTRAINT `fk_event_current_experience_version`
    FOREIGN KEY (`current_experience_version_id`) REFERENCES `event_experience_version` (`event_experience_version_id`);

-- 발행본이 제공하는 프레임. 프레임 삭제는 이 표가 참조하는 발행본이 있으면 막힌다(FRAME-006)
CREATE TABLE `event_frame` (
  `event_frame_id` bigint NOT NULL AUTO_INCREMENT,
  `experience_version_id` bigint NOT NULL,
  `frame_version_id` bigint NOT NULL,
  `layout_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL,
  `is_default` bit(1) NOT NULL,
  PRIMARY KEY (`event_frame_id`),
  UNIQUE KEY `uk_event_frame` (`experience_version_id`,`frame_version_id`),
  KEY `idx_event_frame_frame_version_id` (`frame_version_id`),
  CONSTRAINT `fk_event_frame_experience_version` FOREIGN KEY (`experience_version_id`) REFERENCES `event_experience_version` (`event_experience_version_id`),
  CONSTRAINT `fk_event_frame_frame_version` FOREIGN KEY (`frame_version_id`) REFERENCES `frame_version` (`frame_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
