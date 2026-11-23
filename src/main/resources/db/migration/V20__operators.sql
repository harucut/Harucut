-- V20 — 권한 위임: 공간 역할 OPERATOR, 행사·프로젝트 운영 권한 (ADR-0009 §3, P3-2)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `workspace_membership`
  MODIFY COLUMN `role` enum('ADMIN','DESIGNER','OPERATOR','OWNER','VIEWER') COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE `workspace_invitation`
  MODIFY COLUMN `role` enum('ADMIN','DESIGNER','OPERATOR','OWNER','VIEWER') COLLATE utf8mb4_unicode_ci DEFAULT NULL;

ALTER TABLE `event_grant`
  -- 운영 권한(쉼표 목록: MANAGE_EVENT·MANAGE_BOOTH·INVITE_CUSTOMERS). 공간 역할이 OPERATOR 일 때만 효력이 있다
  ADD COLUMN `operations` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '';

CREATE TABLE `project_membership` (
  `project_membership_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `project_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  -- 그 프로젝트의 모든 행사에 주는 운영 권한·사진 권한(쉼표 목록)
  `operations` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `permissions` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `granted_by_user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`project_membership_id`),
  UNIQUE KEY `uk_project_membership` (`project_id`,`user_id`),
  KEY `idx_project_membership_user_id` (`user_id`),
  CONSTRAINT `fk_project_membership_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`project_id`),
  CONSTRAINT `fk_project_membership_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
