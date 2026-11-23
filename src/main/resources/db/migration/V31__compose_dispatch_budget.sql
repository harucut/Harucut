-- V31 — 앱 재접수 상한(R5 O05). 횟수는 attempt_no(V30), 총 기한은 created_at 으로 본다
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `compose_job`
  -- 이 시각 전에는 다시 던지지 않는다 — claim 때 백오프·지터로 정한다. 아직 던지지 않은 작업은 NULL
  ADD COLUMN `next_attempt_at` datetime(6) DEFAULT NULL;
