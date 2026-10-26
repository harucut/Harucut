-- V15 — 행사 결과 보관 종료 (ADR-0008 §7)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `compose_job`
  -- 보관 기한이 지나 결과·썸네일 객체를 지운 시각(운영자의 삭제 요청 deleted_at 과 다르다)
  ADD COLUMN `purged_at` datetime(6) DEFAULT NULL;

ALTER TABLE `event`
  -- 보관 종료(ARCHIVED)된 시각 — 30일 뒤 부가 행(세션·결과 토큰)을 정리하는 기준
  ADD COLUMN `archived_at` datetime(6) DEFAULT NULL;
