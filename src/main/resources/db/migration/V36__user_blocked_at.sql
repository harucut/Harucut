-- V36 — 정지 시각. 탈퇴 요청으로 상태가 바뀌어도 남아서, 복구하면 ACTIVE 가 아니라 BLOCKED 로 돌아간다(정지 회피 방지)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `users`
  ADD COLUMN `blocked_at` datetime(6) DEFAULT NULL;

UPDATE `users` SET `blocked_at` = `updated_at` WHERE `user_status` = 'BLOCKED';
