-- V4 — 프레임 저장 확장 (ADR-0006 §3): 낙관적 잠금, 컴포넌트 잠금·자산 참조
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

-- 기존 행은 0 에서 시작한다. PUT 에 version 을 안 보내는 기존 클라이언트는 지금처럼 덮어쓴다
ALTER TABLE `frame`
  ADD COLUMN `version` bigint NOT NULL DEFAULT 0;

ALTER TABLE `frame_component`
  ADD COLUMN `locked` bit(1) NOT NULL DEFAULT b'0',
  ADD COLUMN `asset_id` bigint DEFAULT NULL,
  ADD COLUMN `rendered_asset_id` bigint DEFAULT NULL,
  ADD KEY `idx_frame_component_asset_id` (`asset_id`),
  ADD KEY `idx_frame_component_rendered_asset_id` (`rendered_asset_id`),
  ADD CONSTRAINT `fk_frame_component_asset` FOREIGN KEY (`asset_id`) REFERENCES `asset` (`asset_id`),
  ADD CONSTRAINT `fk_frame_component_rendered_asset` FOREIGN KEY (`rendered_asset_id`) REFERENCES `asset` (`asset_id`);
