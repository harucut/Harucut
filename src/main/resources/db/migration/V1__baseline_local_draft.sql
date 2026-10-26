-- V1 — 기준선 초안 (⚠️ 운영 SHOW CREATE TABLE 대조 전)
--
-- 출처: 로컬 MySQL 8.0 (docker compose, Hibernate ddl-auto update 로 생성) 의 mysqldump --no-data,
--       기준 커밋 popeye0618/Harucut main 8bab406.
-- 운영 DB 는 같은 엔티티를 update 로 따라왔지만, update 가 과거에 만든·못 만든 인덱스와 제약이
-- 로컬과 같다는 보장은 없다. 운영 baseline 편입 전에 반드시 운영의
--   SHOW CREATE TABLE <각 테이블>; SHOW INDEX FROM <각 테이블>;
-- 결과와 이 파일을 대조하고, 차이가 있으면 이 파일을 운영 기준으로 고친 뒤 편입한다(R5 17절).
--
-- BATCH_* 메타테이블은 여기 없다 — spring.sql.init 이 Spring Batch 의 schema-mysql.sql 로 만든다
-- (application.yaml). 두 곳에서 만들면 기동 순서에 따라 한쪽이 실패한다.
--
-- 이 파일은 "빈 DB 초기화"용이다. 기존 운영 DB 는 이 파일을 실행하지 않고 flyway baseline 으로
-- 버전 1 에 명시적으로 편입한다 — baseline-on-migrate 자동 편입은 쓰지 않는다.

-- 테이블이 이름순이라 FK 대상(users)이 뒤에 온다. 이 세션에서만 검사를 끈다
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `billing_key` (
  `billing_key_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `billing_key_value` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `masked_card` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pg_provider` enum('MOCK','TOSS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','DELETED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`billing_key_id`),
  KEY `idx_billing_key_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `compose_job` (
  `compose_job_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `failure_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `frame_id` bigint NOT NULL,
  `idempotency_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `media_id` bigint DEFAULT NULL,
  `result_key` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_key_1` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_key_2` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_key_3` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_key_4` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `spec_json` varchar(8000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `status` enum('DONE','FAILED','PENDING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`compose_job_id`),
  UNIQUE KEY `uk_compose_job_user_idempotency` (`user_id`,`idempotency_key`),
  KEY `idx_compose_job_status_started_at` (`status`,`started_at`),
  CONSTRAINT `FKfy4iw9k8qsfpxxd9fen15dtun` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `coupon` (
  `coupon_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `active` bit(1) NOT NULL,
  `code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `grant_tier` enum('BASIC','PLUS','PRO') COLLATE utf8mb4_unicode_ci NOT NULL,
  `max_redemptions` int DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `redeemed_count` int NOT NULL,
  `valid_until` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`coupon_id`),
  UNIQUE KEY `uk_coupon_code` (`code`),
  UNIQUE KEY `UK2ohcw31uak3lcx6d5rfnot0w6` (`public_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `current_terms_version` (
  `current_terms_version_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `terms_id` bigint NOT NULL,
  `terms_version_id` bigint NOT NULL,
  PRIMARY KEY (`current_terms_version_id`),
  UNIQUE KEY `uk_current_terms_version_terms_id` (`terms_id`),
  KEY `FK5849nfhj2k36yrw6kho1decds` (`terms_version_id`),
  CONSTRAINT `FK2hyn03jdf8e1y2wa2ugnwoeng` FOREIGN KEY (`terms_id`) REFERENCES `terms` (`terms_id`),
  CONSTRAINT `FK5849nfhj2k36yrw6kho1decds` FOREIGN KEY (`terms_version_id`) REFERENCES `terms_version` (`terms_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `frame` (
  `frame_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `background` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cell_cutouts` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `frame_type` enum('CLASSIC','GRID','POLAROID','WIDE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_system` bit(1) NOT NULL,
  `preview_key` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`frame_id`),
  KEY `FK7qmvgf0mk5907lm91kjf86nyu` (`user_id`),
  CONSTRAINT `FK7qmvgf0mk5907lm91kjf86nyu` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `frame_component` (
  `frame_component_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `height` double DEFAULT NULL,
  `rendered_key` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `rotation` double NOT NULL,
  `scale` double DEFAULT NULL,
  `source` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL,
  `style_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` enum('PHOTO','STICKER','TEXT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `width` double DEFAULT NULL,
  `x` double NOT NULL,
  `y` double NOT NULL,
  `z_index` int DEFAULT NULL,
  `frame_id` bigint DEFAULT NULL,
  PRIMARY KEY (`frame_component_id`),
  KEY `FK95oagx1i4bqnys1qrscq9rkc4` (`frame_id`),
  CONSTRAINT `FK95oagx1i4bqnys1qrscq9rkc4` FOREIGN KEY (`frame_id`) REFERENCES `frame` (`frame_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `notice` (
  `notice_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `pinned` bit(1) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `published` bit(1) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`notice_id`),
  UNIQUE KEY `uk_notice_public_id` (`public_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment` (
  `payment_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` int NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `failure_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failure_message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `method` enum('BILLING_KEY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `pg_transaction_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('APPROVED','FAILED','REQUESTED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `payment_order_id` bigint NOT NULL,
  PRIMARY KEY (`payment_id`),
  UNIQUE KEY `UK3r9l6fvrhegq39d84q12nje7n` (`public_id`),
  KEY `idx_payment_payment_order_id` (`payment_order_id`),
  CONSTRAINT `FK2jypg32xmcq0022jgkcrhmbya` FOREIGN KEY (`payment_order_id`) REFERENCES `payment_order` (`payment_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `payment_order` (
  `payment_order_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` int NOT NULL,
  `idempotency_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `order_type` enum('INITIAL','RENEWAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('CREATED','FAILED','IN_PROGRESS','PAID') COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_tier` enum('BASIC','PLUS','PRO') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`payment_order_id`),
  UNIQUE KEY `uk_payment_order_idemptency_key` (`idempotency_key`),
  UNIQUE KEY `UK30td8len0e59xmp8bfjdpvmwy` (`public_id`),
  KEY `idx_payment_order_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `terms` (
  `terms_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `active` bit(1) NOT NULL,
  `code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `required` bit(1) NOT NULL,
  `title` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`terms_id`),
  UNIQUE KEY `uk_terms_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `terms_agreement` (
  `terms_agreement_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `agreed` bit(1) NOT NULL,
  `agreed_version` int NOT NULL,
  `user_id` bigint NOT NULL,
  `terms_id` bigint NOT NULL,
  PRIMARY KEY (`terms_agreement_id`),
  UNIQUE KEY `uk_terms_agreement_user_id_terms_id` (`user_id`,`terms_id`),
  KEY `FK6rwjdilpa5s3v889a3mnje1yp` (`terms_id`),
  CONSTRAINT `FK6rwjdilpa5s3v889a3mnje1yp` FOREIGN KEY (`terms_id`) REFERENCES `terms` (`terms_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `terms_agreement_history` (
  `terms_agreement_history_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `agreed` bit(1) NOT NULL,
  `code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `version` int NOT NULL,
  PRIMARY KEY (`terms_agreement_history_id`),
  KEY `idx_terms_agreement_history_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `terms_version` (
  `terms_version_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` int NOT NULL,
  `terms_id` bigint NOT NULL,
  PRIMARY KEY (`terms_version_id`),
  UNIQUE KEY `uk_terms_version_terms_id_version` (`terms_id`,`version`),
  CONSTRAINT `FKr39dxoxdvr383be9y69ok5s96` FOREIGN KEY (`terms_id`) REFERENCES `terms` (`terms_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_coupon` (
  `user_coupon_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `redeemed_at` datetime(6) NOT NULL,
  `status` enum('REDEEMED','RESERVED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  PRIMARY KEY (`user_coupon_id`),
  UNIQUE KEY `uk_user_coupon_user_id_coupon_id` (`user_id`,`coupon_id`),
  UNIQUE KEY `UK9a9sqrnlcxhqbcotf5msirgcy` (`public_id`),
  KEY `idx_user_coupon_user_id` (`user_id`),
  KEY `FK23vpkw483hhbe77dgvimcipf4` (`coupon_id`),
  CONSTRAINT `FK23vpkw483hhbe77dgvimcipf4` FOREIGN KEY (`coupon_id`) REFERENCES `coupon` (`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_media` (
  `media_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `display_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `s3_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `thumbnail_key` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`media_id`),
  UNIQUE KEY `UK6fvxjavomv5m19h4pgxe5o63m` (`s3_key`),
  KEY `FKfxb1n3krkmxfttriemaxl4bnn` (`user_id`),
  CONSTRAINT `FKfxb1n3krkmxfttriemaxl4bnn` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_subscription` (
  `user_subscription_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auto_renew` bit(1) NOT NULL,
  `current_period_end` datetime(6) DEFAULT NULL,
  `current_period_start` datetime(6) DEFAULT NULL,
  `plan_tier` enum('BASIC','PLUS','PRO') COLLATE utf8mb4_unicode_ci NOT NULL,
  `reserved_user_coupon_id` bigint DEFAULT NULL,
  `subscription_status` enum('ACTIVE','CANCELED','EXPIRED','GRANTED','PAST_DUE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`user_subscription_id`),
  UNIQUE KEY `uk_user_subscription_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `users` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `delete_requested_at` datetime(6) DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `profile_image_url` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` enum('APPLE','GOOGLE','HARUCUT','KAKAO','NAVER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `public_id` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_role` enum('ROLE_ADMIN','ROLE_USER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_status` enum('ACTIVE','BLOCKED','DELETED','DELETED_REQUESTED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `username` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_users_public_id` (`public_id`),
  UNIQUE KEY `uk_users_provider_email` (`provider`,`email`),
  UNIQUE KEY `uk_users_provider_provider_id` (`provider`,`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
