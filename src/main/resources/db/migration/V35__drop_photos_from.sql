-- V35 — 새 수신자의 과거 사진 제한(V34)을 뺀다. 초대받으면 그 행사 사진을 전부 본다(사용자 결정, 2026-11-02)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `event_grant`
  DROP COLUMN `photos_from`;

ALTER TABLE `workspace_invitation`
  DROP COLUMN `include_past`;
