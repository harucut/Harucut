-- V27 — 약관 동의의 회원 FK (R5 D12)
-- ⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3). flyway 를 켠 뒤에만 배포한다(V7 참고)
-- ⚠️ 배포 전 고아 행 확인 — 아래가 0 이어야 한다. 0 이 아니면 FK 추가가 실패한다(정리 후 배포)
--   SELECT COUNT(*) FROM terms_agreement ta LEFT JOIN users u ON u.user_id = ta.user_id WHERE u.user_id IS NULL;
-- 회원 행은 탈퇴해도 지우지 않고 익명화한다(UserExitService) — 동의 행은 탈퇴 처리기가 먼저 지운다.
-- 인덱스는 uk_terms_agreement_user_id_terms_id(user_id, terms_id) 의 앞자리를 쓴다

ALTER TABLE `terms_agreement`
  ADD CONSTRAINT `fk_terms_agreement_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
