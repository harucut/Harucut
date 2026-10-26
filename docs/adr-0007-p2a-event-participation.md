# ADR-0007 · P2A 행사 데이터와 인증 — Event·Participant·CaptureRun·BoothDevice·ResultAccess 계약

- **상태**: 합의됨 (프론트 의견 반영 2026-10-26)
- **날짜**: 2026-10-26
- **관련**: R5 4·6·7·13·14·15·16·17절, ADR-0006(P1), `docs/api/p2a-openapi-draft.yaml`
- **프론트 경로**: `/e/{slug}`(행사 입장), `/booth`(부스), `/r#<token>`(개인 결과) — harucut_FE #514

## 0. 원칙

1. **일반 비회원에게는 아무것도 열지 않는다.** 서버 업로드·합성은 회원 경로 또는 유효한 ParticipantSession 으로만 된다.
2. **행사 참가는 촬영 입력만** 서버로 받는다(CAPTURE_INPUT). PHOTO·STICKER·TEXT_RENDER 업로드는 참가자 경로에 없다.
3. **세 가지 자격을 섞지 않는다** — 참가자 토큰, 부스 기기 토큰, 결과 토큰. 서로 대신하지 않고 회원 JWT 도 대신하지 않는다.
4. **행사 합성은 발행본만** 쓴다(FrameVersion). 클라이언트는 frameType·spec·배경색을 보낼 수 없다.
5. 스키마는 Flyway V3+ (ADR-0005). 운영 배포 전 운영 스키마 대조 필수.

## 1. 자격(credential)과 전달 방식

| 자격 | 발급 | 전달 | 저장 | 기본 수명 |
|---|---|---|---|---|
| ParticipantSession | `POST /api/public/events/{slug}/sessions`(BYOD), `POST /api/booth/participant-sessions`(부스) | 헤더 `X-Participant-Token` | SHA-256 해시 | BYOD 12시간, 부스 30분 |
| BoothDevice | `POST /api/public/booth-pairings/redeem` | 헤더 `X-Booth-Token` | SHA-256 해시 + credential_version | 회수 전까지(재발급 시 이전 무효) |
| ResultAccess | 행사 합성 접수 응답 | `Authorization: Bearer <resultToken>` (고정 경로) | SHA-256 해시 | 결과 보관 기한까지 |

- 토큰은 모두 CSPRNG 32바이트 base64url 이다. JWT 가 아니다.
- **쿠키를 쓰지 않는다** → 이 경로들은 CSRF 대상이 아니다. 헤더 전용이라 회원 쿠키가 같이 와도 무시한다.
- BFF: 브라우저는 Next `/api/client/*` 만 부르고, BFF 는 이 경로들에 `X-Participant-Token`·`X-Booth-Token`·`Authorization` 만 넘긴다(쿠키 없음).
- 프론트 저장: 참가자 토큰은 행사·탭별 메모리/sessionStorage(이름공간 분리), 부스 토큰은 부스 프로필의 localStorage,
  결과 토큰은 URL fragment(`/r#<token>`)에서 읽어 메모리로만. 서버로 경로·쿼리에 실어 보내지 않는다.

## 2. 보안 필터 체인

- **행사 전용 체인**을 회원 체인보다 먼저 둔다(`@Order(1)`, `securityMatcher`):
  `/api/public/events/**`, `/api/participant/**`, `/api/booth/**`, `/api/public/booth-pairings/**`, `/api/public/results/**`.
- 이 체인에는 회원 `JwtAuthenticationFilter`·OAuth2 로그인이 **없다**. 대신 세 필터가 각 헤더를 검증해
  `ROLE_PARTICIPANT` / `ROLE_BOOTH` / `ROLE_RESULT_ACCESS` 권한을 만든다. 필터는 빈이 아니라 체인 안에서 직접 생성한다(서블릿 이중 등록 방지).
- 규칙: `GET /api/public/events/{slug}`·`POST /api/public/events/{slug}/sessions`·`POST /api/public/booth-pairings/redeem` 만 permitAll
  (+빈도 제한), `/api/participant/**` = PARTICIPANT, `/api/booth/**` = BOOTH, `/api/public/results/**` = RESULT_ACCESS, 그 외 deny.
- 회원 체인의 마지막 규칙(anyRequest USER/ADMIN)은 그대로라 매처에서 빠진 경로도 보호된다.
- 결과 API 를 이 체인에 반드시 넣는다 — 회원 체인의 JWT 필터는 `Authorization: Bearer` 를 회원 JWT 로 해석해 401 을 낸다.
- 응답 헤더: 행사 체인 전체 `Cache-Control: no-store`, 결과 API 는 추가로 `Referrer-Policy: no-referrer`.
- 약관 동의 인터셉터(TERMS-004)는 회원 주체에만 적용되므로 이 경로에는 걸리지 않는다. 참가 동의는 §5 로 따로 강제한다.
- OpenAPI: 세 스킴을 추가한다 — `participantToken`(apiKey, header `X-Participant-Token`), `boothToken`(apiKey, header `X-Booth-Token`),
  `resultAccess`(http bearer, bearerFormat opaque). 커스터마이저가 이 경로들에 회원 cookieAuth·탈퇴/정지 403 을 붙이지 않게 고친다.

## 3. 데이터 모델 (V3)

| 테이블 | 핵심 컬럼 | 비고 |
|---|---|---|
| `event` | public_id, workspace_id, slug(unique), name, status, paused, capture_starts_at, capture_ends_at, timezone, byod_enabled, booth_enabled, result_retention_days, settings_draft(JSON), settings_version, current_experience_version_id | 상태: DRAFT·LIVE·CAPTURE_CLOSED·ARCHIVED (P2A 는 예약 게시 없음) |
| `event_experience_version` | event_id, version_no, settings(JSON: 허용 레이아웃·프레임·동의 항목·편집 정책 FIXED·브랜드 placeholder), published_by, published_at | 불변 |
| `event_frame` | experience_version_id, frame_version_id, layout_type, sort_order, is_default | 발행본의 프레임 관계 |
| `participant` | public_id, event_id, channel(BYOD·BOOTH), booth_device_id, linked_user_id | 부스는 팀마다 새 행 |
| `participant_session` | participant_id, token_hash(unique), status(RESTRICTED·ACTIVE·REVOKED), expires_at | 동의 전 RESTRICTED |
| `participant_consent` | participant_id, event_id, capture_run_id, item_code, item_version, decision(AGREED·DECLINED·WITHDRAWN), channel, experience_version_id, decided_at | 추가 전용 이력. 회원 terms_agreement 와 별개 |
| `capture_run` | public_id, participant_id, event_id, experience_version_id, booth_device_id, active_booth_device_id(unique, NULL 허용), status(ACTIVE·CLOSED·EXPIRED), state_version, expires_at | 부스당 활성 회차 1개를 유니크로 강제 |
| `booth_device` | public_id, workspace_id, event_id, name, status(PENDING_PAIRING·ACTIVE·REVOKED), token_hash, credential_version, last_seen_at | |
| `booth_pairing` | booth_device_id, code_hash, expires_at, failed_attempts, status(OPEN·REDEEMED·CLOSED) | 코드 원문 저장 안 함 |
| `result_access` | token_hash(unique), compose_job_id, expires_at, revoked_at | 한 job 에 여러 토큰 가능 |
| `asset` (P1) | + event_id, participant_id, capture_run_id, slot_index, bound_compose_job_id | CAPTURE_INPUT 은 한 job 에만 귀속 |
| `compose_job` | + idempotency_scope, event_id, participant_id, capture_run_id, experience_version_id, frame_version_id; user_id NULL 허용 | §6 |

## 4. 운영자(회원) API — 행사 만들기·게시·부스

- `POST /api/auth/workspaces/{workspaceId}/events`, `GET /api/auth/workspaces/{workspaceId}/events`
- `GET /api/auth/events/{eventId}`, `PATCH /api/auth/events/{eventId}`(설정 초안 + `settingsVersion` 낙관적 잠금)
- `POST /api/auth/events/{eventId}/publish` — 검증 후 새 EventExperienceVersion 을 만들고 현재 포인터를 옮긴다.
  검증: 허용 레이아웃 ≥1, 노출 레이아웃마다 연결 프레임 ≥1, 기본 프레임 1개, 프레임 발행본이 그 레이아웃과 같은 frameType,
  발행본 자산이 이 공간 또는 SYSTEM, 동의 항목 ≥1(사진 수신 주체·목적·보관 안내). 실패는 400 `EVENT-003` + 위반 목록.
  **빈 목록이면 기본 카탈로그로 대체하지 않는다.**
- `POST /api/auth/events/{eventId}/pause` · `/resume` · `/close-capture`
- `POST /api/auth/events/{eventId}/booth-devices` → `{ deviceId, pairingCode: "XXXX-XXXX", pairingExpiresAt }`
- `POST /api/auth/booth-devices/{deviceId}/pairing-codes` (재발급 — 이전 코드 폐기), `POST /api/auth/booth-devices/{deviceId}/revoke`
- `GET /api/auth/events/{eventId}/booth-devices`
- 권한: 그 공간의 OWNER·ADMIN(P2A). 계약·이용권 검사는 P2B 에서 추가한다.

## 5. 참가자 API

1. `GET /api/public/events/{slug}` — 참가 정보만: 이름, 상태, 촬영 가능 여부, 참가 방식, 현재 경험 버전 id,
   허용 레이아웃·프레임(id·layout·order·default·미리보기 URL), 동의 항목(code·version·title·required·본문).
   게시 전·삭제·보관 종료는 404. **전체 카탈로그·다른 행사 정보는 내려주지 않는다.**
2. `POST /api/public/events/{slug}/sessions` `{ channel: "BYOD" }` → `{ participantToken, participantId, status: "RESTRICTED", expiresAt }`.
   BYOD 꺼짐 403 `EVENT-002`, 촬영 불가 409 `EVENT-001`, IP 빈도 초과 429 `GEN-061`.
3. `POST /api/participant/events/{eventId}/consents` `{ items: [{ code, version, decision }] }` → `{ status }`.
   decision 은 `AGREED` · `DECLINED` · `WITHDRAWN`(선택 항목 철회). 필수 항목을 DECLINED·WITHDRAWN 하면 RESTRICTED 로 돌아간다.
   현재 경험 버전의 필수 항목을 모두 같은 version 으로 AGREED 해야 ACTIVE. 선택 항목 거부는 막지 않는다.
4. `POST /api/participant/events/{eventId}/capture-runs` → `{ captureRunId, experienceVersionId, stateVersion, expiresAt }`.
   ACTIVE 세션만. 서버가 현재 경험 버전을 부여한다(참가자가 고르지 않는다). 회차 기본 30분.
5. `GET /api/participant/events/{eventId}/frame-versions/{frameVersionId}?captureRunId=` — 그 회차의 경험 버전이 제공한 프레임만.
6. `POST /api/participant/events/{eventId}/upload-intents` `{ captureRunId, slotIndex(0~3), contentType(JPEG·PNG), fileSize }`
   → `{ assetId, uploadUrl, method, headers, expiresAt }`. purpose 는 항상 CAPTURE_INPUT. PUT TTL 10분.
   조건부 PUT 스위치(`storage.upload.conditional-put`)를 P1 과 똑같이 따른다 — 꺼지면 headers 에 If-None-Match 가 없다.
7. `POST /api/participant/assets/{assetId}/complete` — 본인·같은 회차·미귀속 확인 + P1 과 같은 검증.
8. `POST /api/participant/events/{eventId}/compose-jobs` `{ captureRunId, frameVersionId, sourceAssetIds[4], idempotencyKey }`
   → 202 `{ jobId, status, resultToken, resultExpiresAt }`.
   - 접수 시 다시 검사: 세션 ACTIVE·회차 ACTIVE·행사 촬영 가능·일시중지 아님, 프레임이 회차 경험 버전에 포함, 원본 4개가 이 회차의
     VERIFIED CAPTURE_INPUT 이고 다른 job 에 안 묶임. 통과하면 원본 귀속·job·ResultAccess 를 한 트랜잭션으로 저장.
   - 멱등 scope 는 `P:{participantId}`. 같은 키 재요청은 기존 job 을 돌려주고 **새 resultToken 을 하나 더 발급**한다
     (원문을 저장하지 않으므로 이전 것을 다시 보여줄 수 없다. 이전 토큰도 계속 유효).
   - 지문이 다르면 409 `MEDIA-001`.
9. `GET /api/participant/compose-jobs/{jobId}` — 본인 job 만.
10. `GET /api/participant/events/{eventId}/results` — BYOD 세션의 이 행사 내 본인 결과 목록. **부스 세션은 403**(공용 기기에 이전 목록 금지).
11. `GET /api/participant/session` — 새로고침·재방문 때 세션 복원. 무효·만료는 401 `PART-001`.
    → `{ participantId, eventId, eventSlug, channel, status, expiresAt, pendingConsents: [{code, version}], activeCaptureRun?: {captureRunId, stateVersion, expiresAt} }`
12. `POST /api/participant/compose-jobs/{jobId}/download` → `{ url, expiresAt }` — 재방문 목록에서 고른 결과를 내려받는다.
    본인 BYOD 세션만(부스 403 `PART-006`). 결과 API 와 같은 보관·회수·준비 검사(`RESULT-002`·`RESULT-003`).
13. `POST /api/participant/compose-jobs/{jobId}/result-access` → `{ resultToken, resultExpiresAt }` — 공유 링크(`/r#<token>`)를
    다시 만들 때 새 토큰 발급(이전 토큰 유지). 본인 BYOD 세션만(부스 403).

## 6. 합성 주체와 compose_job 전환 (ADR-0007 범위의 migration 순서)

R5 17절 「멱등 scope·User FK의 호환 이관」 순서를 따른다:
1. V3: `idempotency_scope`(NULL 허용) 와 행사 컬럼 추가.
2. 코드: 모든 개인 쓰기가 `U:{userId}` 를 채운다. 조회는 (user, key) 유지.
3. V4: 기존 행 백필 `U:` + user_id. NULL 0건 확인.
4. V5: scope NOT NULL + UNIQUE(idempotency_scope, idempotency_key), `user_id` 선두 일반 인덱스 추가 → 옛 유니크 제거 → user_id NULL 허용.
5. 코드: findStalled 를 LEFT JOIN 으로, 결과·썸네일 key 를 scope 별로(행사: `uploads/events/{eventPublicId}/runs/{runPublicId}/...`),
   completeJob 은 행사 job 이면 UserMedia 를 만들지 않고 job 의 result_key 만 확정, 원본 정리는 귀속 자산 기준.
6. 그다음에 행사 쓰기를 켠다.

행사 key 는 `uploads/` 아래라 `S3Keys.isManagedKey`·Lambda IAM(`uploads/*`)과 맞는다.
행사 job 결과는 P2A 에서 user_media 를 만들지 않는다 — 결과는 compose_job.result_key/thumbnail 이고 ResultAccess 가 그 job 을 가리킨다.
(행사 사진함·고객 열람은 P2B.)

## 7. 부스 API

1. 운영자가 기기를 만들면 연결 코드(base32 8자, `XXXX-XXXX`, 10분)가 나온다.
2. `POST /api/public/booth-pairings/redeem` `{ code }` → `{ boothToken, deviceId, eventId }`.
   성공 1회 원자 소비. 실패 5회면 그 연결 거래 종료(`BOOTH-002`) — 운영자 재발급 필요. IP 분당 30회.
3. `GET /api/booth/me` → 기기 상태 + 배정 행사의 참가 정보(§5-1 과 같은 모양).
4. `POST /api/booth/participant-sessions` → 새 Participant(BOOTH) + RESTRICTED 세션 `{ participantToken, participantId, expiresAt }`.
   기기 회수·미배정 403 `BOOTH-003`, 행사 촬영 불가 409 `EVENT-001`.
5. 이후 동의·회차·업로드·합성은 §5 의 participant 경로를 그 participantToken 으로 쓴다.
   부스 세션으로 회차를 만들 때 **기기당 활성 회차 1개**(유니크) — 이미 있으면 409 `BOOTH-004`.
6. `GET /api/booth/capture-runs/current?version={n}` → 현재 활성 회차의 `{ captureRunId, stateVersion, status, job: { jobId, status }, resultToken? }`.
   `version` 이 서버 stateVersion 과 다르면 job·resultToken 을 싣지 않는다(새 손님 화면에 늦은 결과 노출 방지).
7. `POST /api/booth/capture-runs/{captureRunId}/close` `{ stateVersion }` → 회차 종료 + 그 참가자 세션 회수. 다음 손님은 4번부터.
   stateVersion 불일치 409 `BOOTH-005`.
- 기기 토큰만으로는 업로드·합성·사진 조회를 할 수 없다.

## 8. 결과 수령 API (ResultAccess)

- `GET /api/public/results` (Bearer) → `{ status: PENDING|DONE|FAILED|EXPIRED, eventName, thumbnailUrl?, expiresAt, theme }`.
  `theme` 은 **그 job 에 고정된 경험 버전**의 페이지 테마·브랜드 표시다(P2A 는 settings.theme placeholder 그대로).
  프론트 `/r` 는 초기 HTML 을 중립으로 그리고 이 응답 뒤에 행사 디자인을 입힌다 — 토큰 검증 전에는 행사 정보를 주지 않는다.
- `POST /api/public/results/download` (Bearer) → `{ url, expiresAt }` — 5분 GET URL. 보관·회수·만료를 다시 검사.
- 토큰을 경로·쿼리로 받지 않는다. 첫 조회로 소모하지 않는다(여러 명이 같은 QR 을 스캔한다).
- 잘못된 토큰 시도는 IP 기준으로 따로 제한한다(정상 다회 수령에는 걸리지 않게).

## 9. 오류 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| EVENT-001 | 409 | 지금 촬영할 수 없는 행사(게시 전·일시중지·촬영 종료·기간 밖) |
| EVENT-002 | 403 | 이 참가 방식(BYOD·BOOTH)을 허용하지 않는 행사 |
| EVENT-003 | 400 | 게시 검증 실패 — data 에 위반 목록 |
| EVENT-004 | 409 | slug 중복 |
| EVENT-005 | 409 | 설정 version 불일치 |
| PART-001 | 401 | 참가자 토큰 없음·만료·회수 |
| PART-002 | 403 | 필수 참가 동의가 없다(RESTRICTED) |
| PART-003 | 409 | 회차가 활성 상태가 아니다(종료·만료·다른 참가자) |
| PART-004 | 403 | 이 회차에 제공되지 않은 프레임 |
| PART-005 | 409 | 쓸 수 없는 원본(미검증·다른 회차·이미 다른 job 에 귀속) |
| PART-006 | 403 | 부스 세션에서 허용되지 않는 동작(결과 목록 등) |
| BOOTH-001 | 400 | 연결 코드가 틀렸거나 만료 |
| BOOTH-002 | 410 | 실패 한도로 닫힌 연결 — 운영자 재발급 필요 |
| BOOTH-003 | 403 | 회수됐거나 배정 행사가 없는 기기 / 기기 토큰 무효 |
| BOOTH-004 | 409 | 이 기기에 이미 활성 회차가 있다 |
| BOOTH-005 | 409 | stateVersion 불일치 |
| RESULT-001 | 401 | 결과 토큰 무효·회수 |
| RESULT-002 | 410 | 결과 보관 기한 만료 |
| RESULT-003 | 409 | 아직 준비되지 않았다(다운로드 시) |
| GEN-061 | 429 | 요청 빈도 초과 |

## 10. 설정값(기본값, 모두 `event.*` 설정으로 조정)

참가 세션 BYOD 12h / 부스 30m, 회차 30m, 결과 보관 30일(행사별 `result_retention_days`), 업로드 PUT 10m, 다운로드 GET 5m,
연결 코드 10m·실패 5회, 세션 발급·연결 교환 IP 분당 30회, 결과 잘못된 토큰 IP 분당 30회.
빈도 제한은 Redis INCR+EXPIRE(Lua 원자) — 키는 TTL 필수(64MB noeviction).

## 11. 하지 않는 것

계약·이용권·사용량 원장(P2B), 행사 사진함·고객 열람·ZIP(P2B), 페이지 테마·화이트라벨 상세(P2B — P2A 는 settings 의 placeholder),
참가자 자유 문구(서버 렌더, P2A 후속), 이메일 복구, 계정 연결 UI(모델의 linked_user_id 만), attemptNo·조건부 출력·checksum(별도 계약).
