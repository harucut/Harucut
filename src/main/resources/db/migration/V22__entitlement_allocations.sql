-- V22 — 기업 총량과 행사 배분 (ADR-0009 §5, P3-4)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

ALTER TABLE `entitlement`
  -- 행사 배분(EVENT 범위)의 부모 — 기업 총량(WORKSPACE 범위) 이용권
  ADD COLUMN `parent_entitlement_id` bigint DEFAULT NULL,
  -- 부모일 때: 활성 자식 배분량 합. 조건부 UPDATE(allocated + 요청 <= compose_quota)로만 바꾼다
  ADD COLUMN `allocated_quota` int NOT NULL DEFAULT 0,
  ADD KEY `idx_entitlement_parent` (`parent_entitlement_id`),
  -- 계약 종료의 UPDATE(contract_id 로)가 표 전체를 잠그지 않게
  ADD KEY `idx_entitlement_contract` (`contract_id`),
  ADD CONSTRAINT `fk_entitlement_parent` FOREIGN KEY (`parent_entitlement_id`) REFERENCES `entitlement` (`entitlement_id`);

-- 작업 하나가 부모·자식 두 이용권을 함께 예약한다 — 유니크를 (작업, 이용권)으로. 새 키를 먼저 두어 FK 가 인덱스를 잃지 않게 한다
ALTER TABLE `usage_reservation`
  ADD UNIQUE KEY `uk_usage_reservation_job_entitlement` (`compose_job_id`, `entitlement_id`),
  DROP INDEX `uk_usage_reservation_job`;

ALTER TABLE `usage_ledger`
  ADD UNIQUE KEY `uk_usage_ledger_job_entitlement_kind` (`compose_job_id`, `entitlement_id`, `kind`),
  DROP INDEX `uk_usage_ledger_job_kind`;

-- 활성 배분은 부모·행사마다 하나 — 활성일 때만 값이 있는 생성 칼럼에 유니크(회수·종료하면 NULL 이라 다시 배분할 수 있다).
-- 앱은 부모 잠금 아래에서 먼저 검사하고, 이 키는 그 검사가 빠졌을 때의 불변식이다
ALTER TABLE `entitlement`
  ADD COLUMN `active_allocation` varchar(41) GENERATED ALWAYS AS
    (IF(`status` = 'ACTIVE' AND `parent_entitlement_id` IS NOT NULL, CONCAT(`parent_entitlement_id`, ':', `event_id`), NULL)) STORED,
  ADD UNIQUE KEY `uk_entitlement_active_allocation` (`active_allocation`);
