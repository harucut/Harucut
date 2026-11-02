-- V34 — 새 수신자의 과거 사진 제한(R5 L347, F27). 새 수신자를 더해도 이미 제출된 사진의 열람 범위가 저절로 넓어지지 않게 한다
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

-- 이 시각 이후에 제출된 사진만. NULL 이면 제한 없음(만든 사람, 과거까지 받은 담당자)
ALTER TABLE `event_grant`
  ADD COLUMN `photos_from` datetime(6) DEFAULT NULL;

-- 수락하면 과거 사진까지 받는 초대인가 — 같은 처리 주체 안의 담당자 교체일 때 운영자가 고른다
ALTER TABLE `workspace_invitation`
  ADD COLUMN `include_past` bit(1) NOT NULL DEFAULT b'0';
