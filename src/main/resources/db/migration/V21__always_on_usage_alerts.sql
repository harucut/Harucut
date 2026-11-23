-- V21 — 상시 캠페인과 한도 알림 (ADR-0009 §4, P3-3)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `event`
  -- SCHEDULED(정해진 기간) · ALWAYS_ON(상시 캠페인 — 종료 시각 없음, close-capture 뒤에만 자동 보관 종료). 게시 전에만 바꾼다
  ADD COLUMN `kind` enum('ALWAYS_ON','SCHEDULED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SCHEDULED';

ALTER TABLE `entitlement`
  -- 확정 사용량이 그 비율을 처음 넘은 시각(사용량 응답 alerts[]·감사 로그). 한 번만 채운다
  ADD COLUMN `notified_80_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `notified_95_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `notified_100_at` datetime(6) DEFAULT NULL;
