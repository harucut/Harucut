-- V9 — 행사 참가자·세션·동의·촬영 회차·결과 토큰 (ADR-0007 §3·§5·§8)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)
-- 부스 기기(booth_device)는 V10. 그 FK 는 V10 에서 건다

CREATE TABLE `participant` (
  `participant_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id` bigint NOT NULL,
  `channel` enum('BOOTH','BYOD') COLLATE utf8mb4_unicode_ci NOT NULL,
  `booth_device_id` bigint DEFAULT NULL,
  -- 선택적 계정 연결(P2A 는 모델만)
  `linked_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`participant_id`),
  UNIQUE KEY `uk_participant_public_id` (`public_id`),
  KEY `idx_participant_event_id` (`event_id`),
  CONSTRAINT `fk_participant_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_participant_linked_user` FOREIGN KEY (`linked_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `participant_session` (
  `participant_session_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `participant_id` bigint NOT NULL,
  -- 토큰 원문은 저장하지 않는다
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','RESTRICTED','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`participant_session_id`),
  UNIQUE KEY `uk_participant_session_token_hash` (`token_hash`),
  KEY `idx_participant_session_participant_id` (`participant_id`),
  CONSTRAINT `fk_participant_session_participant` FOREIGN KEY (`participant_id`) REFERENCES `participant` (`participant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 추가 전용 이력. 회원 terms_agreement 와 별개다(비회원은 User 가 없다)
CREATE TABLE `participant_consent` (
  `participant_consent_id` bigint NOT NULL AUTO_INCREMENT,
  `participant_id` bigint NOT NULL,
  `event_id` bigint NOT NULL,
  `capture_run_id` bigint DEFAULT NULL,
  `item_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `item_version` int NOT NULL,
  `decision` enum('AGREED','DECLINED','WITHDRAWN') COLLATE utf8mb4_unicode_ci NOT NULL,
  `channel` enum('BOOTH','BYOD') COLLATE utf8mb4_unicode_ci NOT NULL,
  `experience_version_id` bigint NOT NULL,
  `decided_at` datetime(6) NOT NULL,
  PRIMARY KEY (`participant_consent_id`),
  KEY `idx_participant_consent_participant_id` (`participant_id`),
  CONSTRAINT `fk_participant_consent_participant` FOREIGN KEY (`participant_id`) REFERENCES `participant` (`participant_id`),
  CONSTRAINT `fk_participant_consent_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_participant_consent_experience_version` FOREIGN KEY (`experience_version_id`) REFERENCES `event_experience_version` (`event_experience_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `capture_run` (
  `capture_run_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `participant_id` bigint NOT NULL,
  `event_id` bigint NOT NULL,
  `experience_version_id` bigint NOT NULL,
  `booth_device_id` bigint DEFAULT NULL,
  -- 부스 회차가 ACTIVE 인 동안만 채운다 — 유니크라 기기당 활성 회차가 하나로 강제된다(NULL 은 여러 개 허용)
  `active_booth_device_id` bigint DEFAULT NULL,
  `status` enum('ACTIVE','CLOSED','EXPIRED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `state_version` int NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`capture_run_id`),
  UNIQUE KEY `uk_capture_run_public_id` (`public_id`),
  UNIQUE KEY `uk_capture_run_active_booth_device` (`active_booth_device_id`),
  KEY `idx_capture_run_participant_id` (`participant_id`),
  CONSTRAINT `fk_capture_run_participant` FOREIGN KEY (`participant_id`) REFERENCES `participant` (`participant_id`),
  CONSTRAINT `fk_capture_run_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_capture_run_experience_version` FOREIGN KEY (`experience_version_id`) REFERENCES `event_experience_version` (`event_experience_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 행사 합성 작업의 범위
ALTER TABLE `compose_job`
  ADD COLUMN `event_id` bigint DEFAULT NULL,
  ADD COLUMN `participant_id` bigint DEFAULT NULL,
  ADD COLUMN `capture_run_id` bigint DEFAULT NULL,
  ADD COLUMN `experience_version_id` bigint DEFAULT NULL,
  ADD COLUMN `frame_version_id` bigint DEFAULT NULL,
  ADD KEY `idx_compose_job_participant_id` (`participant_id`),
  ADD CONSTRAINT `fk_compose_job_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  ADD CONSTRAINT `fk_compose_job_participant` FOREIGN KEY (`participant_id`) REFERENCES `participant` (`participant_id`),
  ADD CONSTRAINT `fk_compose_job_capture_run` FOREIGN KEY (`capture_run_id`) REFERENCES `capture_run` (`capture_run_id`),
  ADD CONSTRAINT `fk_compose_job_experience_version` FOREIGN KEY (`experience_version_id`) REFERENCES `event_experience_version` (`event_experience_version_id`),
  ADD CONSTRAINT `fk_compose_job_frame_version` FOREIGN KEY (`frame_version_id`) REFERENCES `frame_version` (`frame_version_id`);

-- 촬영 입력(CAPTURE_INPUT)의 회차 귀속과 "한 job 에만" 귀속
ALTER TABLE `asset`
  ADD COLUMN `event_id` bigint DEFAULT NULL,
  ADD COLUMN `participant_id` bigint DEFAULT NULL,
  ADD COLUMN `capture_run_id` bigint DEFAULT NULL,
  ADD COLUMN `slot_index` int DEFAULT NULL,
  ADD COLUMN `bound_compose_job_id` bigint DEFAULT NULL,
  ADD KEY `idx_asset_capture_run_id` (`capture_run_id`),
  ADD CONSTRAINT `fk_asset_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  ADD CONSTRAINT `fk_asset_participant` FOREIGN KEY (`participant_id`) REFERENCES `participant` (`participant_id`),
  ADD CONSTRAINT `fk_asset_capture_run` FOREIGN KEY (`capture_run_id`) REFERENCES `capture_run` (`capture_run_id`),
  ADD CONSTRAINT `fk_asset_bound_compose_job` FOREIGN KEY (`bound_compose_job_id`) REFERENCES `compose_job` (`compose_job_id`);

-- 결과 수령 토큰. 한 job 에 여러 개가 있을 수 있다(같은 키 재요청·공유 링크 재발급)
CREATE TABLE `result_access` (
  `result_access_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `compose_job_id` bigint NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`result_access_id`),
  UNIQUE KEY `uk_result_access_token_hash` (`token_hash`),
  KEY `idx_result_access_compose_job_id` (`compose_job_id`),
  CONSTRAINT `fk_result_access_compose_job` FOREIGN KEY (`compose_job_id`) REFERENCES `compose_job` (`compose_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
