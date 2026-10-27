# 테스트 전용 인증서 — 운영에 쓰지 않는다

Apple App Store 서명(JWS x5c) 검증 테스트용으로 만든 자체 서명 체인이다. 실제 Apple 인증서가 아니다.

- `root.pem` — 테스트 신뢰 루트(운영에서는 Apple Root CA - G3 를 `billing.store.apple.root-certificates` 로 준다)
- `intermediate.pem` — Apple 중간 인증서 OID(1.2.840.113635.100.6.2.1) 표시
- `leaf.pem`·`leaf.key.pem` — 서명 인증서 OID(1.2.840.113635.100.6.11.1) 표시, 테스트가 이 키로 JWS 를 서명한다
- `other-root.pem` — 체인과 무관한 루트(신뢰하지 않는 루트 검증용)

다시 만들 때는 `ext.cnf` 의 확장으로 openssl 에서 root → intermediate → leaf 순으로 서명한다(ADR-0011 §2).
