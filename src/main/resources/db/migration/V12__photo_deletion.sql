-- V12 — 행사 사진함의 삭제 요청과 목록 색인 (ADR-0008 §3)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `compose_job`
  -- 사진함에서 삭제 요청된 시각. 목록·ZIP·결과 링크에서 빠지고 결과 객체는 커밋 후 지운다
  ADD COLUMN `deleted_at` datetime(6) DEFAULT NULL,
  -- 사진함 커서(최신순) — 행사 안에서 (created_at, id) 내림차순
  ADD KEY `idx_compose_job_event_created` (`event_id`, `created_at`, `compose_job_id`);
