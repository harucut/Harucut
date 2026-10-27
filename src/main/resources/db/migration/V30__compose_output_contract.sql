-- V30 — 합성 출력 무결성: 시도 번호·출력 계약·확정한 체크섬 쌍, 지연 재삭제 (R5 D09)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `compose_job`
  -- 앱이 실행기에 넘긴 횟수 — claim 마다 +1. 실패 통지는 지금 번호와 같을 때만 반영한다. 기존 행은 0(예전 payload 는 번호 없음)
  ADD COLUMN `attempt_no` int NOT NULL DEFAULT 0,
  -- 출력 계약(1=예전, 2=조건부 생성·체크섬 쌍 검증). 접수 때 고정. 기존 행은 NULL(예전 계약)
  ADD COLUMN `render_contract` int DEFAULT NULL,
  -- 계약 2 로 완료를 확정할 때 검증한 결과·썸네일 SHA-256(base64)
  ADD COLUMN `result_sha256` varchar(44) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `thumbnail_sha256` varchar(44) COLLATE utf8mb4_unicode_ci DEFAULT NULL;

ALTER TABLE `s3_deletion`
  -- 지운 뒤 이 시각에 한 번 더 지운다(늦게 도착한 실행이 고정 key 에 다시 쓴 객체)
  ADD COLUMN `redelete_at` datetime(6) DEFAULT NULL;
