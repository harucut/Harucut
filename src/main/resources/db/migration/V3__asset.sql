-- V3 — 공통 이미지 자산 (ADR-0006 §2)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

CREATE TABLE `asset` (
  `asset_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- NULL = SYSTEM 범위(관리자 자산). 개인·행사 자산은 그 공간
  `workspace_id` bigint DEFAULT NULL,
  `purpose` enum('CAPTURE_INPUT','FRAME_ASSET','PAGE_THEME_ASSET','PROFILE','TEXT_RENDER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `origin` enum('CREATOR_BROWSER','SERVER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `s3_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `declared_size` bigint NOT NULL,
  `actual_size` bigint DEFAULT NULL,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `status` enum('PENDING_UPLOAD','REJECTED','VERIFIED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `reject_reason` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `uploaded_by_user_id` bigint DEFAULT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`asset_id`),
  UNIQUE KEY `uk_asset_public_id` (`public_id`),
  UNIQUE KEY `uk_asset_s3_key` (`s3_key`),
  KEY `idx_asset_workspace_id` (`workspace_id`),
  -- 미참조 자산 정리 배치가 "오래된 것부터" 훑는다
  KEY `idx_asset_created_at` (`created_at`),
  CONSTRAINT `fk_asset_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_asset_uploaded_by` FOREIGN KEY (`uploaded_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
