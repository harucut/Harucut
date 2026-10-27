# ADR-0011 · P4 앱 — 앱 로그인(시스템 브라우저·PKCE)과 스토어 구독

- **상태**: 2판 — §1 구현(P4-A), §2 구현(P4-B)
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

## 2. 스토어 구독 (P4-B)

### 2-1. 모델 (V24)

- `user_subscription.source`: `WEB`(PortOne 정기결제) · `APP_STORE` · `PLAY_STORE` · `COUPON`(무료 부여). BASIC·만료면 NULL.
  기존 행은 유료(ACTIVE·CANCELED·PAST_DUE)면 WEB, GRANTED 면 COUPON 으로 채운다.
- `store_purchase`: 사용자·플랫폼·스토어 키(애플 originalTransactionId · 구글 purchaseToken)와 그 해시(유니크 platform+해시 — **한 구매는 한 계정**)·
  상품·등급·상태(ACTIVE·CANCELED·PAST_DUE·EXPIRED·REVOKED)·자동갱신·만료·환경·마지막 검증 시각.
- 상품 → 등급은 설정 `billing.store.products`(기본 `harucut.plus.monthly`=PLUS, `harucut.pro.monthly`=PRO — 콘솔 상품 id 와 맞출 것).

### 2-2. API

| API | 요청 | 응답 | 오류 |
|---|---|---|---|
| `GET /api/auth/subscriptions` | — | 기존 + `source`(BASIC 이면 키 없음) + `storeAccountToken`(UUID, 계정마다 고정) | — |
| `POST /api/auth/subscriptions/store-purchases` | `{platform, productId, proof}` — proof: 애플 `Transaction.jwsRepresentation` · 구글 `purchaseToken` | 내 구독 | SUBS-007·008·009(data.source)·010 |
| `POST /api/auth/subscriptions/store-purchases/restore` | `{platform, items:[{productId, proof}]}`(1~20) | 내 구독(복원할 것 없으면 그대로) | SUBS-008(유효한 것이 모두 남의 것)·009 |
| `POST /api/auth/subscriptions/cancel` | — | — | 스토어 구독이면 SUBS-009(data.source) |
| `POST /api/auth/payments/subscribe` | — | — | 스토어로 구독 중이면 SUBS-009(data.source), 웹·쿠폰은 지금처럼 PAY-003 |
| `POST /api/payments/webhooks/app-store` | `{signedPayload}`(ASSN v2) | — | PAY-008 |
| `POST /api/payments/webhooks/google-play?token=` | Pub/Sub 푸시 봉투(RTDN) | — | PAY-008 |

- 앱은 구매할 때 `storeAccountToken` 을 애플 `appAccountToken`·구글 `obfuscatedAccountId` 로 싣는다. 검증된 구매에 다른 계정의 표시가 있으면 SUBS-008.
- 규칙: 유료 출처는 하나 — 웹·다른 스토어로 지금 유료 등급을 받고 있으면 SUBS-009(data.source). 쿠폰 무료 부여는 스토어 구매가 대신한다.
  같은 플랫폼의 다른 구매는 바꿔 끼운다. 같은 구매를 다시 보내면 최신 상태로 맞춘다(멱등).

### 2-3. 검증

- 검증기 인터페이스 `AppStoreVerifier`·`PlayStoreVerifier`. `billing.store.verifier=mock`(기본, 로컬·테스트 — 증빙 `mock:{JSON}`, 운영에서는
  `STORE_MOCK_ALLOWED` 없이 기동하지 않는다) · `real`.
- 애플(real): JWS ES256, x5c 체인을 설정 루트(`billing.store.apple.root-certificates` — Apple Root CA - G3)까지 PKIX 검증(폐기 확인 없음),
  서명 인증서 OID 1.2.840.113635.100.6.11.1·중간 OID 1.2.840.113635.100.6.2.1, 번들 id, 자동 갱신 구독, 샌드박스 허용(`allow-sandbox`, 앱 심사용 기본 허용).
  알림은 중첩된 거래·갱신 정보 JWS 까지 같은 검증 — 자동갱신 상태·결제 재시도·환불을 읽는다. 네트워크 없이 검증하고, 테스트는 자체 서명 체인으로 한다.
- 구글(real): Play Developer API 가 필요해 **아직 붙이지 않았다** — real 이면 구글 구매는 SUBS-007 로 거절된다(조용히 통과시키지 않는다).

### 2-4. 상태 반영

- 등록·복원·알림 모두 「이 계정의 이 플랫폼 구매 중 지금 등급을 주는 것(가장 늦게 끝나는 것)」으로 구독을 맞춘다. 없으면 그 플랫폼이 출처였을 때만 강등.
  자동갱신 끔 → CANCELED(기한까지 유료), 결제 재시도 → PAST_DUE, 만료·환불 → BASIC.
- 앱이 다시 보낸 증빙은 옛 스냅샷일 수 있다(환불·자동갱신 끔이 거래 JWS 에 없다) — 이미 있는 구매는 저장값보다 늦게 끝나는(새 갱신의) 증빙일 때만
  바꾼다. 그래서 알림이 반영한 REVOKED·CANCELED 를 옛 증빙이 되돌리지 못하고, 그 결과 유효하지 않은 구매의 등록은 SUBS-007(복원은 건너뜀)이다.
- 스토어 만료로 강등할 때 예약 쿠폰이 있으면 만료 배치와 같은 규칙으로 쿠폰을 이어 받는다(GrantActivationService).
- 알림: 같은 notificationUUID 는 한 번(webhook_receipt). 등록되지 않은 구매는 건너뛴다(앱의 등록·복원이 반영). 늦게 온 옛 알림은 기한을 되돌리지 않는다
  (환불만 예외 — 저장된 상품·기한을 남기면 등급도 저장값). 그 사이 웹으로 옮긴 계정은 구독을 건드리지 않고 구매만 갱신한다. 구글 RTDN 은 「다시 조회하라」 신호 — 푸시 URL 토큰을 확인하고 Play 로 다시 읽는다.
- 배치: 갱신 배치는 웹(source WEB·NULL)만 청구한다. 만료 배치는 스토어 구독 중 자동갱신을 끈 것은 기한에, 갱신 중(ACTIVE·PAST_DUE)인데 알림이 없으면
  `billing.store.grace-days`(3일) 뒤에 강등한다. 읽기 시점 등급은 기한에서 바로 BASIC(effectiveTier).
- 잠금: 등록·복원은 구독 행 잠금(사용자 public id 로)이 트랜잭션의 첫 문장 — RR 스냅샷이 잠금 뒤에 잡혀 먼저 끝난 요청·알림을 본다. 구매 행은
  INSERT IGNORE 로 넣은 뒤 잠금 읽기(현재 읽기)로 주인을 확인한다(없는 키 잠금 읽기의 gap lock 교착을 피한다). 알림은 구매의 주인을 트랜잭션 밖에서
  읽고(바뀌지 않는다) 반영 트랜잭션을 구독 행 잠금으로 시작한다. 순서는 늘 구독 행 → 구매 행.

### 2-5. 실연동 미확인

App Store Connect(상품 id·번들·서버 알림 URL 등록, Apple Root CA - G3 설정), Google Play Console(상품·서비스 계정·Play Developer API 검증기 구현·
RTDN Pub/Sub 토픽과 푸시 URL 토큰), sandbox 실기기 구매·복원·환불 확인.
