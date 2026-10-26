-- V19 — 프로젝트와 브랜드 기본값 상속 (ADR-0009 §2, P3-1)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)
-- V18 은 feat/s3-deletion-outbox(#34) 가 쓴다 — #34 를 먼저 머지한다.

CREATE TABLE `project` (
  `project_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `workspace_id` bigint NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  -- 행사 설정의 상속 가능한 필드(JSON, 행사 설정과 같은 이름). null 이면 이 단계의 값 없음
  `brand_defaults` mediumtext COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  -- 보관하면 새 행사를 지정할 수 없다(기존 행사는 그대로)
  `archived_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`project_id`),
  UNIQUE KEY `uk_project_public_id` (`public_id`),
  KEY `idx_project_workspace_id` (`workspace_id`),
  CONSTRAINT `fk_project_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `workspace`
  -- 공간(기업) 단위 기본값 — 프로젝트·행사가 덮는다
  ADD COLUMN `brand_defaults` mediumtext COLLATE utf8mb4_unicode_ci DEFAULT NULL;

ALTER TABLE `event`
  ADD COLUMN `project_id` bigint DEFAULT NULL,
  ADD KEY `idx_event_project_id` (`project_id`),
  ADD CONSTRAINT `fk_event_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`project_id`);

-- 지금까지의 행사는 모든 값을 행사가 직접 정한 것으로 둔다 — 초안(직접 값)에 컬럼 값을 옮겨, 상위 기본값이 생겨도 바뀌지 않게.
-- 컬럼(timezone·byod_enabled·booth_enabled·result_retention_days)은 계속 「지금 적용되는 값」으로 남는다
UPDATE `event`
   SET `settings_draft` = JSON_SET(`settings_draft`,
         '$.timezone', `timezone`,
         '$.byodEnabled', IF(`byod_enabled` = 1, CAST('true' AS JSON), CAST('false' AS JSON)),
         '$.boothEnabled', IF(`booth_enabled` = 1, CAST('true' AS JSON), CAST('false' AS JSON)),
         '$.resultRetentionDays', `result_retention_days`);
