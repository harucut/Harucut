-- V17 — 프레임 발행 번호 카운터 (fix/entity-update-races)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)
-- 번호를 frame_version 범위의 잠금 읽기(max+1 FOR UPDATE)로 매기면, 서로 다른 프레임의 첫 발행이
-- uk_frame_version_frame_no 의 같은 빈 구간에 gap lock 을 함께 잡고 서로의 insert 를 기다려 교착된다.
-- 잠근 frame 행의 카운터를 +1 해 매긴다(행 잠금만). 기존 프레임은 지금 최대 번호로 채운다.
-- V16 은 feat/p2b-portone-webhook(#29) 이 쓴다.

ALTER TABLE `frame`
  ADD COLUMN `last_version_no` INT NOT NULL DEFAULT 0;

UPDATE `frame` f
  JOIN (SELECT `frame_id`, MAX(`version_no`) AS `max_no` FROM `frame_version` GROUP BY `frame_id`) v
    ON v.`frame_id` = f.`frame_id`
   SET f.`last_version_no` = v.`max_no`;
