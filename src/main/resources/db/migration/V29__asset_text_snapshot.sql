-- V29 — 참가자 자유 문구 서버 렌더의 스냅샷 (R5 D06)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `asset`
  -- 서버 생성 TEXT_RENDER(origin=SERVER) 만 — 어떤 문구·스타일로 구웠는지. 같은 멱등키 재요청이 이 값으로 대조한다
  ADD COLUMN `text_snapshot` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `text_style_id` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
