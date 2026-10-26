-- V18 — S3 삭제 예약(재시도 일반화)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)
-- deleteAfterCommit 이 호출자 트랜잭션 안에서 이 행을 남기고, 커밋 뒤 삭제에 성공하면 지운다.
-- 실패분은 주기 배치가 백오프로 다시 지우고, 상한(attempts)을 넘으면 남겨 둔다(운영자가 건수·로그로 본다).
-- 같은 key 는 한 행(key_hash 유니크 — s3_key 1024자는 utf8mb4 유니크 인덱스 길이를 넘는다).

CREATE TABLE `s3_deletion` (
  `s3_deletion_id` bigint NOT NULL AUTO_INCREMENT,
  `s3_key` varchar(1024) NOT NULL,
  `key_hash` char(64) NOT NULL,
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`s3_deletion_id`),
  UNIQUE KEY `uk_s3_deletion_key_hash` (`key_hash`),
  KEY `idx_s3_deletion_next_attempt_at` (`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
