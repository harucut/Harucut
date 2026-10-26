# ADR-0011 · P4 앱 — 앱 로그인(시스템 브라우저·PKCE)과 스토어 구독

- **상태**: 1판 — §1 구현(P4-A), §2 합의된 계획(P4-B, 다음 PR)
- **날짜**: 2026-10-26
- **관련**: R5 12·16절, FE `docs/app-shell-backend-requests.md` §1, ADR-0008(PortOne 웹훅 패턴)

## 0. 원칙

1. 앱 셸은 웹을 WebView 로 띄운다. 서버 계약은 **웹과 같은 쿠키 세션**을 유지하고, 앱만의 토큰 체계를 따로 만들지 않는다.
2. 키·콘솔 설정이 필요한 실연동(스토어 결제·알림, 실기기 확인)은 하지 않고 「실연동 미확인」으로 남긴다. 그 앞까지는 검증기 인터페이스와 목으로 코드·테스트를 끝낸다.

## 1. 앱 로그인 (P4-A)

구글은 임베디드 WebView 의 OAuth 를 막는다(`disallowed_useragent`). 앱은 시스템 브라우저(Custom Tabs·ASWebAuthenticationSession)로 로그인해야 하는데,
그 브라우저의 쿠키는 WebView 로 넘어오지 않는다. 그래서 시스템 브라우저에는 쿠키 대신 **일회용 코드**를 주고, WebView 가 그 코드를 쿠키로 바꾼다.

| 단계 | 계약 |
|---|---|
| 시작 | `GET /oauth2/authorization/{provider}?client=app&code_challenge=<S256, base64url 43자>` (`code_challenge_method` 는 없거나 `S256`) |
| 성공 | `harucut://oauth2/callback?code=<일회용 코드>` — 쿠키를 심지 않는다 |
| PKCE 값 없음·모양 틀림·plain | `harucut://oauth2/callback?error=invalid_request` — 교환할 수 없는 코드는 주지 않는다 |
| 소셜 인증 실패·취소 | `harucut://oauth2/callback?error=oauth2` |
| 교환 | `POST /api/harucut/oauth2/exchange {code, codeVerifier}` → 200, `/api/harucut/login` 과 같은 두 쿠키·같은 본문(`userStatus`) |
| 교환 실패 | 400 `AUTH-041`(없음·만료·사용됨·검증값 불일치를 하나로), 본문 형식 오류는 400 `GEN-003` |

- 코드: CSPRNG 32바이트 base64url. Redis 에는 해시만 키로(`oauth2:app:code:{sha256}`), 값은 사용자 public id + code_challenge, **60초·1회용**
  (`getAndDelete` — 검증값이 틀려도 소모돼 추측을 반복할 수 없다).
- PKCE(RFC 7636 S256): 다른 앱이 `harucut://` 를 가로채 코드를 얻어도 verifier 가 없으면 교환하지 못한다. 비교는 상수 시간.
- 돌아갈 주소는 설정 `app.oauth2.callback-url`(기본 `harucut://oauth2/callback`) 한 곳뿐 — 요청 값으로 바꿀 수 없다(열린 redirect 없음).
- 앱 진입 표시는 인가 요청 속성에 실어 세션의 인가 요청과 함께 콜백까지 보낸다(`AppAwareAuthorizationRequestResolver`). 콜백 필터가
  꺼낸 인가 요청을 요청 속성에 남겨(`RememberingAuthorizationRequestRepository`) 성공·실패 핸들러가 읽는다 — 사용자가 취소한 오류 응답도 같다.
- 교환 때 역할·상태는 지금 값으로 다시 읽는다(코드 발급 뒤 60초 사이의 변경 반영). 교환 경로는 로그인 없이 열린다(코드·verifier 가 자격).
- 웹 흐름(`client` 없음)은 그대로다. 카카오·네이버는 WebView 안에서 진행해도 된다.
- 실연동 미확인: 실기기 구글 로그인(기존 OAuth 클라이언트로 충분할 것으로 보지만 리디렉션 등록은 콘솔에서 확인).

## 2. 스토어 구독 (P4-B, 계획)

- 스키마: `user_subscription.source`(WEB·APP_STORE·PLAY_STORE·GRANTED), `store_purchase`(플랫폼·원거래 id/구매 토큰 해시 유니크·상품·만료·상태·
  마지막 검증·환경).
- API: 구독 조회에 `source`, `POST /api/auth/subscriptions/store-purchases`(iOS StoreKit2 JWS · Android purchaseToken 검증 → 활성화),
  `POST /api/auth/subscriptions/store-purchases/restore`, 웹훅 `POST /api/payments/webhooks/app-store`(ASSN v2 JWS)·`/google-play`(RTDN).
- 규칙: 유료 출처는 하나(다른 출처로 구독 중이면 409), 한 구매는 한 계정(다른 계정에 묶였으면 409 — 복원이 조용히 옮기지 않는다).
- 검증기 인터페이스 + 목(운영에서는 목 금지). Apple JWS 체인 검증은 공개 루트 인증서만으로 가능해 자체 서명 체인으로 단위 테스트한다.
- 오류 코드(FE 문구 반영됨): SUBS-007(400 검증 실패), SUBS-008(409 다른 계정의 구매), SUBS-009(409 다른 출처로 구독 중), SUBS-010(400 알 수 없는 상품).
- 실연동 미확인: App Store Connect·Google Play Console(상품·키·알림 설정), sandbox 실기기 구매·복원.
