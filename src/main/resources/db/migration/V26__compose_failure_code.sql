-- V26 — 합성 실패 종류 (app-shell §8)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `compose_job`
  -- FAILED 일 때 실패 종류 — 화면 문구를 고르는 값. 코드를 알리지 않는 실행기의 실패는 UNKNOWN, 이전 행은 NULL
  ADD COLUMN `failure_code` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
