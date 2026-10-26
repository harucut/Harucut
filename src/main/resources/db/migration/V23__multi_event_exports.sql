-- V23 — 여러 행사 묶음 다운로드 (ADR-0009 §6, P3-5)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `export_job`
  -- EVENT(행사 하나 — 지금까지) · PROJECT · WORKSPACE(여러 행사를 요청에 명시해 담는다)
  ADD COLUMN `scope` enum('EVENT','PROJECT','WORKSPACE') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EVENT',
  ADD COLUMN `workspace_id` bigint DEFAULT NULL,
  -- PROJECT 일 때만
  ADD COLUMN `project_id` bigint DEFAULT NULL,
  -- 여러 행사 묶음은 행사 하나에 묶이지 않는다
  MODIFY COLUMN `event_id` bigint DEFAULT NULL,
  ADD KEY `idx_export_job_scope_requester` (`workspace_id`, `scope`, `requested_by_user_id`),
  ADD CONSTRAINT `fk_export_job_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`workspace_id`),
  ADD CONSTRAINT `fk_export_job_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`project_id`);

-- 지금까지의 묶음은 모두 행사 하나 — 그 행사의 공간으로 채운다
UPDATE `export_job` j JOIN `event` e ON e.`event_id` = j.`event_id` SET j.`workspace_id` = e.`workspace_id`;

ALTER TABLE `export_job`
  MODIFY COLUMN `workspace_id` bigint NOT NULL;
