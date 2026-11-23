-- V11 — 멤버·행사 고객 초대, 행사 사진 권한(EventGrant), 감사 로그 (ADR-0008 §2)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `workspace_invitation` (
  `workspace_invitation_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  -- EVENT 초대(행사 고객)일 때만. 수락하면 공간 멤버가 아니라 EventGrant 가 생긴다
  `event_id` bigint DEFAULT NULL,
  `kind` enum('EVENT','WORKSPACE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` enum('ADMIN','DESIGNER','OWNER','VIEWER') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- EVENT 초대의 사진 권한(쉼표 목록)
  `permissions` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 표시용 메모(메일을 보내지 않는다)
  `email` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `note` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 초대 토큰의 SHA-256. 원문은 저장하지 않는다
  `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `status` enum('ACCEPTED','OPEN','REVOKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `invited_by_user_id` bigint NOT NULL,
  `accepted_by_user_id` bigint DEFAULT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`workspace_invitation_id`),
  UNIQUE KEY `uk_workspace_invitation_public_id` (`public_id`),
  UNIQUE KEY `uk_workspace_invitation_token_hash` (`token_hash`),
  KEY `idx_workspace_invitation_workspace_id` (`workspace_id`),
  CONSTRAINT `fk_workspace_invitation_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_workspace_invitation_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_workspace_invitation_invited_by` FOREIGN KEY (`invited_by_user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_workspace_invitation_accepted_by` FOREIGN KEY (`accepted_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `event_grant` (
  `event_grant_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  -- VIEW_PHOTOS·DOWNLOAD_PHOTOS·BULK_DOWNLOAD·DELETE_PHOTOS 의 쉼표 목록
  `permissions` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `granted_by_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`event_grant_id`),
  UNIQUE KEY `uk_event_grant` (`event_id`,`user_id`),
  KEY `idx_event_grant_user_id` (`user_id`),
  CONSTRAINT `fk_event_grant_event` FOREIGN KEY (`event_id`) REFERENCES `event` (`event_id`),
  CONSTRAINT `fk_event_grant_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_event_grant_granted_by` FOREIGN KEY (`granted_by_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `audit_log` (
  `audit_log_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `actor_user_id` bigint DEFAULT NULL,
  `workspace_id` bigint DEFAULT NULL,
  `event_id` bigint DEFAULT NULL,
  `action` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_id` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 원문 토큰·연락처는 넣지 않는다
  `detail` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`audit_log_id`),
  KEY `idx_audit_log_workspace_id` (`workspace_id`,`audit_log_id`),
  KEY `idx_audit_log_event_id` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이미 있는 행사: 만든 사람에게 네 권한을 준다(새 행사는 만들 때 앱이 준다)
INSERT INTO `event_grant` (`created_at`, `updated_at`, `event_id`, `user_id`, `permissions`, `granted_by_user_id`)
SELECT NOW(6), NOW(6), e.`event_id`, e.`created_by_user_id`, 'VIEW_PHOTOS,DOWNLOAD_PHOTOS,BULK_DOWNLOAD,DELETE_PHOTOS', NULL
FROM `event` e;
