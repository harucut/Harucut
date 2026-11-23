-- V6 — 서버 초안 (ADR-0006 §5)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

CREATE TABLE `frame_draft` (
  `frame_draft_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  -- NULL = 아직 저장 안 한 새 프레임의 초안
  `frame_id` bigint DEFAULT NULL,
  -- 편집기 문서. 서버는 해석하지 않는다
  `document` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `revision` int NOT NULL,
  PRIMARY KEY (`frame_draft_id`),
  UNIQUE KEY `uk_frame_draft_public_id` (`public_id`),
  KEY `idx_frame_draft_user_id` (`user_id`),
  KEY `idx_frame_draft_frame_id` (`frame_id`),
  CONSTRAINT `fk_frame_draft_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_frame_draft_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_frame_draft_frame` FOREIGN KEY (`frame_id`) REFERENCES `frame` (`frame_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초안 문서가 쓰는 자산(요청의 assetIds). 서버가 문서를 해석하지 않으므로 이 표가 정리 배치의 참조 근거다
CREATE TABLE `frame_draft_asset` (
  `frame_draft_asset_id` bigint NOT NULL AUTO_INCREMENT,
  `frame_draft_id` bigint NOT NULL,
  `asset_id` bigint NOT NULL,
  PRIMARY KEY (`frame_draft_asset_id`),
  UNIQUE KEY `uk_frame_draft_asset` (`frame_draft_id`,`asset_id`),
  KEY `idx_frame_draft_asset_asset_id` (`asset_id`),
  CONSTRAINT `fk_frame_draft_asset_draft` FOREIGN KEY (`frame_draft_id`) REFERENCES `frame_draft` (`frame_draft_id`),
  CONSTRAINT `fk_frame_draft_asset_asset` FOREIGN KEY (`asset_id`) REFERENCES `asset` (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
