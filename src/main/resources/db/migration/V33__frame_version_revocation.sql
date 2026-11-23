-- V33 — 프레임 발행본 긴급 회수(R5 L297·L302·L396). 게시·접수에서 막고, 이미 접수한 작업과 결과는 그대로 둔다
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `frame_version`
  ADD COLUMN `revoked_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `revoked_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
