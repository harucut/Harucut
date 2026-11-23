-- V5 — 불변 발행본 (ADR-0006 §4)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

CREATE TABLE `frame_version` (
  `frame_version_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `frame_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `frame_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- FrameSpec v2 JSON. 8000자 상한이 있는 compose_job.spec_json 과 달리 발행본은 제한을 두지 않는다
  `spec_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`frame_version_id`),
  UNIQUE KEY `uk_frame_version_frame_no` (`frame_id`,`version_no`),
  CONSTRAINT `fk_frame_version_frame` FOREIGN KEY (`frame_id`) REFERENCES `frame` (`frame_id`),
  CONSTRAINT `fk_frame_version_created_by` FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 발행본이 참조하는 자산. 미참조 자산 정리 배치가 이 표를 "참조"로 센다
CREATE TABLE `frame_version_asset` (
  `frame_version_asset_id` bigint NOT NULL AUTO_INCREMENT,
  `frame_version_id` bigint NOT NULL,
  `asset_id` bigint NOT NULL,
  PRIMARY KEY (`frame_version_asset_id`),
  UNIQUE KEY `uk_frame_version_asset` (`frame_version_id`,`asset_id`),
  KEY `idx_frame_version_asset_asset_id` (`asset_id`),
  CONSTRAINT `fk_frame_version_asset_version` FOREIGN KEY (`frame_version_id`) REFERENCES `frame_version` (`frame_version_id`),
  CONSTRAINT `fk_frame_version_asset_asset` FOREIGN KEY (`asset_id`) REFERENCES `asset` (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
