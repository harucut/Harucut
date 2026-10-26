-- V7 — 합성 작업의 멱등 범위를 사용자에서 "주체(scope)"로 바꾼다 (ADR-0007 §6, R5 17절)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3).
-- ⚠️ 이 migration 부터는 flyway 를 켠 뒤에만 배포할 수 있다. flyway 가 꺼진 운영의 Hibernate update 는
--    옛 유니크(uk_compose_job_user_idempotency)를 지우지도, user_id 의 NOT NULL 을 풀지도 못한다.
--
-- R5 17절은 구형 인스턴스가 남은 롤링 배포를 전제로 여러 배포에 나눠 적는다. 이 서비스는 단일 인스턴스를
-- 내렸다 올리는 배포라 같은 버전의 코드가 항상 scope 를 채운다 — 그래서 한 migration 에서 순서대로 한다.

-- 1) scope·출력 위치 컬럼(NULL 허용으로 먼저)
ALTER TABLE `compose_job`
  ADD COLUMN `idempotency_scope` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `storage_root` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL;

-- 2) 기존 행 백필 — 지금까지의 작업은 전부 개인 작업이다
UPDATE `compose_job` j
JOIN `users` u ON u.`user_id` = j.`user_id`
SET j.`idempotency_scope` = CONCAT('U:', j.`user_id`),
    j.`storage_root` = CONCAT('uploads/users/', u.`public_id`, '/');

-- 3) NOT NULL + 새 유니크
ALTER TABLE `compose_job`
  MODIFY `idempotency_scope` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  MODIFY `storage_root` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  ADD UNIQUE KEY `uk_compose_job_scope_idempotency` (`idempotency_scope`,`idempotency_key`);

-- 4) 옛 유니크는 user_id FK 를 받치는 유일한 인덱스다(로컬 SHOW INDEX 확인) — 대체 인덱스를 먼저 만든 뒤 지운다
ALTER TABLE `compose_job` ADD KEY `idx_compose_job_user_id` (`user_id`);
ALTER TABLE `compose_job` DROP INDEX `uk_compose_job_user_idempotency`;

-- 5) 행사 참가자 작업은 User 가 없다
ALTER TABLE `compose_job` MODIFY `user_id` bigint DEFAULT NULL;
