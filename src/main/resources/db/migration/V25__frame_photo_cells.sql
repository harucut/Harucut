-- V25 — 자유 사진 칸 (ADR-0012, P5)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `frame`
  -- 칸 4개의 위치·크기·회전·모양·촬영 순번(JSON). NULL 이면 frame_type 의 고정 레이아웃
  ADD COLUMN `photo_cells` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
