-- V32 — 결과 보관 약정 스냅샷(R5 L330, O31), 합성 입력 지문(R5 L484·L490)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `compose_job`
  -- 결과 보관 약정. 접수 때 굳고, 행사 보관 일수를 늘리면 함께 는다. 개인 작업은 NULL
  ADD COLUMN `retain_until` datetime(6) DEFAULT NULL,
  -- spec·입력 key·출력 계약의 SHA-256. 이 값이 생기기 전 작업은 NULL(대조하지 않는다)
  ADD COLUMN `input_fingerprint` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL;

UPDATE `compose_job` j
  JOIN `event` e ON e.`event_id` = j.`event_id`
  SET j.`retain_until` = TIMESTAMPADD(DAY, e.`result_retention_days`, j.`created_at`);

-- 결과 링크 만료는 작업의 약정을 따른다 — 토큰마다 두면 약정을 늘려도 이전 링크가 옛 기한에 끊긴다
ALTER TABLE `result_access` DROP COLUMN `expires_at`;
