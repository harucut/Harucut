-- V37 — 공간 종류(업체/주최자, 사용자 결정 2026-11-03). HOST 는 지금과 같고(행사를 만든 사람이 사진 권한을 받는다),
-- ENTERPRISE 는 공간 멤버가 사진을 보지 않고 고객(EventGrant)만 본다. 기존 공간은 전부 HOST(지금 동작 보존)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3)

ALTER TABLE `workspace`
  ADD COLUMN `kind` enum('ENTERPRISE','HOST') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'HOST';
