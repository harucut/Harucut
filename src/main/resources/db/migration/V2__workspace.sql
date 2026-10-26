-- V2 — 개인 Workspace 최소 모델 (ADR-0006 §1)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 가 꺼진 운영에서는 Hibernate update 가
--    테이블·컬럼만 만들고 아래 백필은 돌지 않는다 — 코드가 개인 공간을 지연 생성하고 frame.workspace_id NULL 을 허용한다.

CREATE TABLE `workspace` (
  `workspace_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` enum('ORGANIZATION','PERSONAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- PERSONAL 일 때만 채운다. 유니크라 사용자당 개인 공간이 하나로 강제된다(NULL 은 여러 개 허용)
  `personal_owner_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`workspace_id`),
  UNIQUE KEY `uk_workspace_public_id` (`public_id`),
  UNIQUE KEY `uk_workspace_personal_owner` (`personal_owner_user_id`),
  CONSTRAINT `fk_workspace_personal_owner` FOREIGN KEY (`personal_owner_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `workspace_membership` (
  `workspace_membership_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `workspace_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role` enum('ADMIN','DESIGNER','OWNER','VIEWER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','REMOVED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`workspace_membership_id`),
  UNIQUE KEY `uk_workspace_membership` (`workspace_id`,`user_id`),
  KEY `idx_workspace_membership_user_id` (`user_id`),
  CONSTRAINT `fk_workspace_membership_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  CONSTRAINT `fk_workspace_membership_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `frame`
  ADD COLUMN `workspace_id` bigint DEFAULT NULL,
  ADD KEY `idx_frame_workspace_id` (`workspace_id`),
  ADD CONSTRAINT `fk_frame_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`);

-- 백필: 탈퇴 완료(DELETED) 사용자를 뺀 모든 사용자에게 개인 공간 + OWNER 멤버십.
-- public_id 는 12자 무작위 hex. 충돌하면 유니크가 migration 을 멈춘다(확률은 무시할 수준)
INSERT INTO `workspace` (`created_at`, `updated_at`, `public_id`, `type`, `name`, `personal_owner_user_id`)
SELECT NOW(6), NOW(6), SUBSTRING(SHA2(CONCAT(UUID(), '-', `user_id`), 256), 1, 12), 'PERSONAL', '내 공간', `user_id`
FROM `users`
WHERE `user_status` <> 'DELETED';

INSERT INTO `workspace_membership` (`created_at`, `updated_at`, `workspace_id`, `user_id`, `role`, `status`)
SELECT NOW(6), NOW(6), `workspace_id`, `personal_owner_user_id`, 'OWNER', 'ACTIVE'
FROM `workspace`
WHERE `type` = 'PERSONAL';

-- 사용자 프레임만. 시스템 프레임(user_id NULL)은 SYSTEM 범위라 workspace_id 를 비워 둔다
UPDATE `frame` f
JOIN `workspace` w ON w.`personal_owner_user_id` = f.`user_id`
SET f.`workspace_id` = w.`workspace_id`
WHERE f.`user_id` IS NOT NULL;
