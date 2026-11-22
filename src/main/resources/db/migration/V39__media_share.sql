-- V39 — 개인 사진 공유 링크(/s/<token>, 사용자 결정 2026-11-16). 받는 사람은 로그인 없이 사진만 보고 저장한다.
-- 사진 하나에 링크 하나 — 다시 눌러도 같은 링크다. 열림·닫힘은 저장하지 않고 열 때마다 본다(사진이 주인 기록에 보이는 동안만)
-- 토큰은 해시가 아니라 원문을 둔다 — 같은 링크를 다시 돌려줘야 해서다(docs/decisions.md 2026-11-16)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)

CREATE TABLE `media_share` (
  `media_share_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `media_id` bigint NOT NULL,
  -- CSPRNG 32바이트 base64url(43자). 대소문자가 다른 토큰은 다른 토큰이라 이진 비교한다
  `token` char(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`media_share_id`),
  UNIQUE KEY `uk_media_share_media_id` (`media_id`),
  UNIQUE KEY `uk_media_share_token` (`token`),
  CONSTRAINT `fk_media_share_media` FOREIGN KEY (`media_id`) REFERENCES `user_media` (`media_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
