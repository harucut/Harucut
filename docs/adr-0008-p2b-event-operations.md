# ADR-0008 · P2B 단일 행사·부스 운영 — 멤버·고객 열람·사진함·ZIP·테마·계약·이용권·사용량·PortOne

- **상태**: 초안 · 프론트 의견 반영(2026-10-26, #22) — 2-4 의 (a)(b)는 사용자 확인 대기
- **날짜**: 2026-10-26
- **관련**: R5 2·3·7·8·9·10·11·12·14·20절, ADR-0006(P1), ADR-0007(P2A, §12 구현 반영), `docs/api/p2b-openapi-draft.yaml`
- **완료 조건(R5 20절)**: 링크와 부스 양쪽에서 지정 디자인·프레임으로 촬영, 참가자·주최자·고객 재다운로드 및 실패 처리

## 0. 원칙

1. **사진 접근은 행사 단위 권한(EventGrant)으로만 연다.** 공간 역할만으로 사진을 보지 않는다. 운영 권한(설정·게시)과 사진 권한(열람·다운로드·일괄·삭제)은 따로 준다(R5 3절).
2. **권한 밖의 행사는 존재도 알리지 않는다.** 접근 범위 밖은 404(`GEN-031`). 범위 안이지만 사진 권한이 없으면 403 `PHOTO-001`.
3. 목록·상세·개별 다운로드·ZIP 생성·ZIP 수령이 **같은 권한 검사**를 매번 다시 한다. URL 의 id 를 바꿔도 범위가 넓어지지 않는다.
4. 계약·이용권은 결제와 분리한다. 실제 돈이 오가지 않은 혜택은 ManualGrant 로 남기고 결제를 PAID 로 꾸미지 않는다(R5 10절).
5. 합성 사용량은 **성공한 네컷 1장 = 1건**. 예약은 합성 접수와 같은 트랜잭션, 확정·반환은 결과 전이와 같은 트랜잭션에서 **정확히 한 번**(R5 12절).
6. 실연동이 필요한 것(PortOne 키·실 S3 ZIP 업로드)은 코드·테스트까지 하고 PR 에 「실연동 미확인」으로 적는다.

## 1. 단계 나눔 (각각 별도 PR, 이 순서)

| 단계 | 내용 | 새 테이블(migration) |
|---|---|---|
| P2B-1 | 멤버 초대·역할 변경·회수, 행사 고객 초대, EventGrant, 감사 로그 | V11 `workspace_invitation`, `event_grant`, `audit_log` |
| P2B-2 | 사진함(커서 목록·필터·개별 다운로드·삭제), 행사 복제 | V12 `compose_job.deleted_at` |
| P2B-3 | ZIP 묶음 다운로드(비동기 작업) | V13 `export_job` |
| P2B-4 | 페이지 테마·브랜딩(theme 스키마 확정, PAGE_THEME_ASSET 업로드) | 없음(설정 JSON) |
| P2B-5 | 문의·계약·이용권·ManualGrant(CMS), 사용량 예약·확정·반환, 게시·합성·부스 한도 강제, 사용량 조회 | V14 `inquiry`, `contract`, `entitlement`, `usage_reservation`, `usage_ledger` |
| P2B-6 | 보관 종료 배치(결과 정리·행사 ARCHIVED), 결과 확정 후 촬영 입력 행 정리 | 없음 |
| P2B-7 | PortOne V2 웹훅 검증(Standard Webhooks)·결제 조회 대조, 계약 결제 증빙 연결 — **실연동 미확인** | V15 `webhook_receipt` |

P2C 로 넘기는 것: attemptNo·실행별 출력 위치·승자 참조(R5 13절 — Lambda 변경·배포가 필요), 부하·모니터링.
P3 로 넘기는 것: 프로젝트·상시 캠페인·기업 총량과 행사 배분의 2단 예약.

## 2. 멤버와 행사 고객 (P2B-1)

### 2-1. 공간 멤버
- 역할은 지금처럼 `OWNER · ADMIN · DESIGNER · VIEWER`. 의미:
  - OWNER·ADMIN: 공간·행사 설정, 게시, 부스, 멤버·고객 초대, EventGrant 관리. **사진은 EventGrant 가 있어야 본다**(자기에게 줄 수 있다).
  - DESIGNER: 공간 프레임·테마 자산 제작. 게시·부스·사진 없음.
  - VIEWER: 공간 프레임 조회만.
- `POST /api/auth/workspaces/{workspaceId}/invitations { role, email? }` (OWNER·ADMIN) → `{ invitationId, inviteToken, expiresAt }`
  - 초대 토큰은 32바이트 불투명 값, 7일, **원문은 이 응답에서만**. 링크는 `/invite#<token>`(fragment — 서버 로그에 남지 않게).
  - email 은 표시용 메모다(메일 발송은 하지 않는다). 수락은 토큰을 가진 로그인 사용자 누구나 — 링크 전달이 곧 초대.
  - OWNER 초대는 OWNER 만 할 수 있다.
- `POST /api/auth/invitations/preview { token }` (로그인) → `{ kind, workspaceName, eventName?, role?, permissions?, invitedByName, expiresAt }`
  — 수락 전에 무엇에 들어가는지 보여 준다. 소비하지 않는다. 토큰을 쿼리에 싣지 않으려고 POST. 오류는 수락과 같다(MEMBER-001·002).
  FE 흐름: `/invite#<token>` → sessionStorage 에 잠깐 → (로그인) → 미리보기 → 수락.
- `POST /api/auth/invitations/accept { token }` (로그인) → `{ kind: WORKSPACE|EVENT, workspaceId?, eventId?, role?, permissions? }`
  - 1회 소비(조건부 UPDATE). 만료 410 `MEMBER-001`, 틀림·이미 사용 400 `MEMBER-002`, 이미 멤버 409 `MEMBER-003`.
- `GET /api/auth/workspaces/{workspaceId}/members` (멤버 누구나) → `[{ userId(public), nickname, role, joinedAt }]`
- `PATCH /api/auth/workspaces/{workspaceId}/members/{userId} { role }`, `DELETE …/members/{userId}` (OWNER·ADMIN, OWNER 변경은 OWNER 만)
  - 마지막 OWNER 를 내리거나 내보낼 수 없다 409 `MEMBER-004`. 본인 탈퇴(`DELETE …/members/me`)도 같은 규칙.
  - 회수하면 그 사람의 이 공간 EventGrant 도 함께 회수하고, 진행 중인 그 사람의 ZIP 작업은 수령이 막힌다(§4).
- `GET /api/auth/workspaces/{workspaceId}/invitations`, `POST …/invitations/{invitationId}/revoke`

### 2-2. 행사 고객과 EventGrant
- EventGrant = (사용자, 행사, 권한 집합). 권한: `VIEW_PHOTOS · DOWNLOAD_PHOTOS · BULK_DOWNLOAD · DELETE_PHOTOS`.
  설정 변경은 EventGrant 가 아니라 공간 역할(OWNER·ADMIN)이 맡는다.
- 행사 고객(신랑신부 등)은 **공간 멤버가 아니다** — 공간의 다른 행사 이름·사진 수도 보지 못한다(R5 3절).
- `POST /api/auth/events/{eventId}/invitations { permissions[], note? }` (OWNER·ADMIN) → 초대 토큰(위와 같은 규칙, kind EVENT)
- 수락하면 EventGrant 만 생긴다. 이미 같은 행사 권한이 있으면 권한을 합친다.
- `GET /api/auth/events/{eventId}/grants`, `PATCH …/grants/{grantId} { permissions[] }`, `DELETE …/grants/{grantId}` (OWNER·ADMIN)
- `GET /api/auth/me/events` → 내가 권한을 가진 행사(멤버로서 운영하는 행사 + EventGrant 받은 행사)
  `[{ eventId, name, status, access: { role?, permissions[] } }]`. 고객에게는 이 목록이 Studio 의 시작점이다.
- **행사를 만든 사람**에게는 그 행사의 EventGrant(네 권한 전부)를 자동으로 준다 — 단일 행사 주최자가 바로 사진을 보도록.
- 기존 P2A 행사(이미 만든 행사)는 V11 에서 만든 사람에게 같은 EventGrant 를 백필한다.

### 2-3. 감사 로그
- `audit_log(actor_user_id, workspace_id, event_id, action, target_type, target_id, detail JSON, created_at)`
- 기록: 게시, 설정 변경(프레임·테마·브랜딩 필드), 일시중지·재개·촬영 종료, 부스 등록·재발급·회수, 멤버·초대·EventGrant 변경,
  사진 삭제, ZIP 생성·수령, CMS(계약·이용권·ManualGrant) 변경. 원문 토큰·개인 연락처는 넣지 않는다.
- 조회: `GET /api/auth/workspaces/{workspaceId}/audit-logs?cursor` (OWNER·ADMIN), `GET /api/admin/audit-logs` (플랫폼 ADMIN).

### 2-4. 바꾸기 쉽게 둘 정책 (사용자 확인 대기)
- (a) 공간 OWNER·ADMIN 도 사진은 EventGrant 로만 본다 — 판정은 `EventAccessPolicy` 한 곳에 둔다.
- (b) 초대 수락에 이메일 일치 검사를 하지 않는다 — 수락 검사는 `InvitationService` 한 곳에 둔다.
- 사용자 결정이 바뀌면 그 한 곳만 고친다.

## 3. 사진함 (P2B-2)

- `GET /api/auth/events/{eventId}/photos?cursor&limit&from&to&channel&frameVersionId&status`
  - 권한: 그 행사의 `VIEW_PHOTOS`. 정렬은 최신순, 커서는 불투명 문자열(`(createdAt, jobId)` 인코딩). limit 기본 30, 최대 100.
  - 응답 `{ items: [{ photoId(=jobId), status: PENDING|DONE|FAILED, createdAt, channel: BYOD|BOOTH, frameVersionId,
    thumbnailUrl?, failure?: { code, retryable } }], nextCursor? }`
  - thumbnailUrl 은 presigned GET **5분**(`storage.download.get-ttl`). 목록을 다시 부르면 새 URL.
  - 실패 사유는 내부 예외 문자열이 아니라 코드(`COMPOSE_FAILED · INPUT_MISSING · TIMEOUT`)와 재시도 가능 여부만.
- `POST /api/auth/events/{eventId}/photos/{photoId}/download` (`DOWNLOAD_PHOTOS`) → `{ url, expiresAt }` 5분.
  URL 에 `response-content-disposition`(RFC 5987 `filename*`)으로 「{행사명}-{photoId}.jpg」를 붙인다.
- `DELETE /api/auth/events/{eventId}/photos/{photoId}` (`DELETE_PHOTOS`) → 삭제 요청
  - job 에 `deleted_at` 을 기록하고 결과·썸네일 객체를 커밋 후 지운다. 목록·ZIP·참가자 결과 링크에서 즉시 빠진다
    (결과 링크는 `RESULT-002` 410 과 같은 「만료」로 보인다 — 삭제 사실을 참가자에게 따로 알리지 않는다).
  - 이미 만든 ZIP 에 그 사진이 있으면 그 ZIP 을 무효로 한다(§4).
- 행사 복제 `POST /api/auth/events/{eventId}/clone { name, slug }` (OWNER·ADMIN) → 새 DRAFT 행사.
  설정·테마·제공 프레임 연결만 복제한다. 사진·참가자·세션·결과 토큰·고객 초대·EventGrant(만든 사람 것 제외)·동의 기록·부스는 복제하지 않는다(R5 7절).

## 4. ZIP 묶음 다운로드 (P2B-3)

- `POST /api/auth/events/{eventId}/exports { photoIds?: [], filter?: { from, to, channel, frameVersionId } }` (`BULK_DOWNLOAD`)
  → 202 `{ exportId, status: PENDING }`. 대상은 접수 시점의 DONE·미삭제 사진. 최대 2,000장(넘으면 400 `EXPORT-003`).
- `GET /api/auth/events/{eventId}/exports/{exportId}` → `{ exportId, status: PENDING|RUNNING|DONE|FAILED|EXPIRED, photoCount, sizeBytes?, expiresAt? }`
- `POST /api/auth/events/{eventId}/exports/{exportId}/download` → `{ url, expiresAt }` 5분. 준비 전 409 `EXPORT-001`, 만료·무효 410 `EXPORT-002`.
  파일 이름은 「{행사명}-{YYYYMMDD}.zip」(접수 날짜, `response-content-disposition`·RFC 5987).
- `GET /api/auth/events/{eventId}/exports` — 내가 만든 최근 작업(다른 사람의 작업은 보이지 않는다).
- 처리: 앱 안의 비동기 실행기(동시 1개)가 결과 객체를 차례로 읽어 ZIP 으로 스트리밍하고 S3 multipart 로 올린다.
  키는 `uploads/events/{eventPid}/exports/{exportPid}.zip`. API 요청 하나가 압축을 맡지 않는다.
- 수명: DONE 뒤 24시간에 객체를 지우고 EXPIRED. **수령 때마다 권한을 다시 본다** — 만든 사람의 `BULK_DOWNLOAD` 가 회수됐거나
  포함 사진이 삭제됐으면 410 이고 객체를 지운다. 이미 발급한 5분 URL 의 즉시 회수는 보장하지 않는다(R5 8절, 짧은 수명으로 대응).
- 서버가 재시작되면 RUNNING 작업은 FAILED 로 정리하고 다시 만들게 한다(재개하지 않는다).

## 5. 페이지 테마·브랜딩 (P2B-4)

`EventSettings.theme` 을 확정한다. 자유 CSS·임의 URL 은 받지 않는다 — 허용 목록 값과 자산 참조만.

```json
{
  "schemaVersion": 1,
  "colors": { "bg": "#FFF8F3", "card": "#FFFFFF", "text": "#2B2B2B", "muted": "#6B6B6B",
              "border": "#E8DED6", "accent": "#C8553D", "accentContrast": "#FFFFFF" },
  "font": "PRETENDARD",
  "logoAssetId": "As12…",
  "backgroundAssetId": "As34…",
  "copy": { "welcome": "…", "consentIntro": "…", "captureGuide": "…", "composing": "…", "done": "…",
            "expired": "…", "closed": "…", "error": "…" },
  "branding": { "hideHarucutBranding": false, "outputLogo": "NONE" }
}
```

- colors: `#RRGGBB` 7개 고정 이름. **FE 토큰과 1:1**(FE `.ev-surface`):
  `bg→--ev-bg · card→--ev-card · text→--ev-text · muted→--ev-muted · border→--ev-border · accent→--ev-accent · accentContrast→--ev-accent-contrast`.
  빠진 색은 하루컷 기본값을 쓴다(서버가 채워서 내려준다). `--ev-danger`(오류 문구)는 가독성 때문에 테마로 열지 않는다.
  색 대비는 서버가 막지 않는다 — Studio 가 text↔bg, accentContrast↔accent 대비 4.5:1 미만이면 경고한다.
- font: `SYSTEM · PRETENDARD · NANUM_MYEONGJO · GOWUN_DODUM · BLACK_HAN_SANS` 중 하나(웹폰트 로딩은 FE).
- logoAssetId·backgroundAssetId: 이 공간의 VERIFIED `PAGE_THEME_ASSET`(PNG·JPEG). 업로드는 기존 자산 intent 에
  `purpose: PAGE_THEME_ASSET` + `workspaceId` (OWNER·ADMIN·DESIGNER) — P1 자산 흐름 그대로.
- copy: 화면별 문구, 각 200자 이내, 일반 텍스트(HTML 아님). 빠지면 FE 기본 문구.
- branding.hideHarucutBranding: 하루컷 UI 표시 숨김. **이용권 기능 `WHITE_LABEL` 이 있어야 게시된다**(없으면 게시 위반).
  branding.outputLogo: `NONE · ORGANIZER_LOGO` — 완성 이미지에 로고를 넣는 정책. P2B 는 값만 저장·발행했고, 합성 반영은 2026-10-27(OutputLogoPlacer): 회차의 경험 발행본이 ORGANIZER_LOGO 면 `theme.logoAssetId` 를 맨 위 레이어로 — 오른쪽 아래, 여백 = 캔버스 너비 3%, 너비 = 캔버스 너비 18%(원본 비율, 높이가 캔버스 12% 를 넘으면 높이 12%). 미리보기도 같은 규칙. ORGANIZER_LOGO 인데 로고가 없으면 게시 위반. 기존 레이어로 그리므로 Lambda 변경 없음.
- 게시 검사(EVENT-003 위반 목록에 추가): 색 형식, 허용 폰트, 문구 길이, 자산이 이 공간의 VERIFIED PAGE_THEME_ASSET, WHITE_LABEL 기능.
- 공개 행사 응답·부스 me 의 `theme` 은 발행본 값에 기본값을 채우고 자산 id 대신 **URL**(`logoUrl`·`backgroundUrl`, presigned GET 24시간)을 싣는다.
  결과 API(`GET /api/public/results`)의 `theme` 도 같은 모양(그 job 에 고정된 경험 버전 기준).
- 미참조 자산 정리(P1-6)에 PAGE_THEME_ASSET 참조 규칙을 더한다 — 행사 설정 초안·발행본 JSON 의 자산 id 를 참조로 센다.

## 6. 계약·이용권·사용량 (P2B-5)

### 6-1. CMS (플랫폼 ADMIN, `/api/admin/**`)
- 문의 `inquiry`: 접수 `POST /api/public/inquiries`(로그인 없이, IP 분당 5회) `{ contactName, email, phone?, organizerType: PERSONAL|BUSINESS,
  channels: [BYOD, BOOTH], expectedDate?, expectedCount?, message }` → 201 `{ inquiryId }`.
  ADMIN: 목록·상세·`PATCH { status: NEW|CONTACTED|QUOTED|WON|LOST, assigneeNote }`.
- 계약 `contract`: `{ workspaceId, contractorName, scope: WORKSPACE|EVENT, eventIds?, amount, currency, startsAt, endsAt,
  paymentEvidence: { kind: PORTONE|BANK_TRANSFER|OTHER, reference }, status: DRAFT|ACTIVE|ENDED|CANCELLED }`.
  ACTIVE 로 바꿀 때 이용권을 만든다. CANCELLED·ENDED 는 이용권을 끝낸다(진행 중 예약은 그대로 확정·반환된다).
- 이용권 `entitlement`: `{ scope: WORKSPACE|EVENT, workspaceId, eventId?, composeQuota, retentionDays, maxBoothDevices,
  maxLiveEvents, features: [WHITE_LABEL, CUSTOM_THEME, BULK_DOWNLOAD], validFrom, validTo, source: CONTRACT|MANUAL, contractId? }`
- ManualGrant: `POST /api/admin/entitlements` 를 `source: MANUAL` + `{ grantorNote(사유) }` 로. 부여자·사유·기간·한도를 남긴다.
  회수·만료 전에 `GET /api/admin/entitlements/{id}/impact` 로 영향(사용 중 행사·남은 예약)을 보여 준다.
  사용 중 행사 = 지금 그 행사에 **적용되는** 이용권이 이것인 LIVE 행사(행사 범위 이용권에 덮였거나 이미 끝난 이용권이면 제외).

### 6-2. 적용
- 행사에 적용되는 이용권 = 유효기간 안의 **행사 범위 > 공간 범위** 중 하나(가장 구체적인 것, 같으면 validTo 가 늦은 것).
- **게시**: 적용 이용권이 없으면 403 `ENT-001`. 공간의 LIVE 행사 수가 maxLiveEvents 이상이면 게시 위반.
- **합성 접수**: 새 job 일 때만(멱등 재생은 제외) 예약 1건. `usage_reservation(job_id unique, entitlement_id, status RESERVED|COMMITTED|RELEASED)` 를
  job 저장과 같은 트랜잭션에 넣고, 이용권 카운터는 조건부 UPDATE
  `reserved = reserved + 1 WHERE status = ACTIVE AND valid_from <= now < valid_to AND used + reserved + 1 <= compose_quota`
  — 0행이면 409 `ENT-002`(참가 화면: 「촬영 한도에 도달」). 후보를 읽은 뒤 계약이 끝나도(validTo 단축) 끝난 이용권에 예약하지 않는다.
  이미 잡힌 예약의 확정·반환은 유효기간과 무관하다.
- **확정·반환**: `completeJob` 의 PENDING→DONE 전이를 이긴 트랜잭션 안에서 `RESERVED→COMMITTED`(used+1, reserved−1),
  `failJob` 전이 안에서 `RESERVED→RELEASED`(reserved−1). 예약 행의 조건부 UPDATE 가 **COMMIT·RELEASE 중 하나만** 허용한다.
  늦게 온 성공·실패는 이미 종결된 예약을 되살리지 않는다. `usage_ledger` 에 RESERVE·COMMIT·RELEASE 이력을 남긴다(감사용).
- 다운로드·결과 링크 재방문·부스 회차 닫기는 차감하지 않는다. 개인 합성은 이 원장과 무관하다(개인 요금제 그대로).
- **부스 기기 수**: 적용 이용권의 maxBoothDevices 가 설정값(20)을 대신한다.
- **보관 기간**: 이용권 retentionDays 가 있으면 행사 resultRetentionDays 의 상한이 된다. PATCH 에서 넘으면 400 `GEN-002`
  (게시 뒤에도 넘기지 못하게), 게시 검사에서도 한 번 더 본다.
- **동시 운영 상한**: 게시 검사의 maxLiveEvents 는 공간 행을 잠근 뒤 **잠금 읽기(FOR UPDATE)로** 센다 — 동시에 두 행사를 게시해도 상한을 넘지 않는다.
  MySQL REPEATABLE READ 에서는 잠금 전 일반 읽기로 스냅샷이 잡히므로, 잠금 뒤에 일반 읽기로 세면 방금 커밋된 LIVE 를 놓친다. 부스 기기 수도 같은 방식.
- **기존 LIVE 행사(이 변경 전에 게시된 것)**: 적용 이용권이 없으면 회차 시작·합성 접수가 409 `ENT-002` 가 된다(게시 관문을 거치지 않았으므로).
  배포 전에 운영 DB 에서 이용권 없는 LIVE·CAPTURE_CLOSED 행사를 세어 보고, 있으면 ManualGrant 를 먼저 부여한다 — 사용자 확인 후 진행.
- 회차 시작에서도 잔량이 0 이면 409 `ENT-002` — 찍고 나서 막히지 않게(접수에서 다시 검사한다).

### 6-3. 조회
- `GET /api/auth/events/{eventId}/usage` (OWNER·ADMIN, 또는 그 행사 EventGrant 보유자)
  → `{ entitlement: { scope, validFrom, validTo, features[] }?, composeQuota, used, reserved, remaining, retentionDays, maxBoothDevices }`
  적용 이용권이 없으면 entitlement 키 없음 + 수치 0.
- 행사 고객에게는 계약 금액·다른 행사 정보가 나가지 않는다.

## 7. 보관 종료와 정리 (P2B-6)

- 행사 결과의 보관 기한 = job 생성 시각 + resultRetentionDays(ADR-0007 그대로). 매일 04:00 배치가 기한 지난 결과·썸네일 객체를 지우고
  job 에 `purged_at` 을 기록한다(사진함·ZIP·결과 링크에서 EXPIRED). 행사의 모든 결과가 정리되고 촬영이 끝났으면 ARCHIVED.
  영구 삭제라 기준 시각은 **배치(트랜잭션)마다** 행사 행을 잠금 읽기(FOR UPDATE)로 다시 읽어 계산한다 — 실행 도중 운영자가 보관 일수를 늘리면
  그 뒤 배치는 새 기준을 쓰고, 배치가 커밋할 때까지 설정 저장이 기다린다. 보관 종료(ARCHIVED)는 트랜잭션 안에서 읽은 행사의 @Version 으로 지킨다.
- 보관 종료와 참가자 합성 접수는 **같은 행사 행 잠금**으로 줄을 선다: 접수는 행사를 공유 잠금으로 다시 읽은 값으로 촬영 열림을 확인하고,
  보관 종료는 행사를 쓰기 잠금으로 잡은 뒤 남은 작업을 잠금 읽기로 다시 센다. 검사를 통과하고 커밋 전인 접수가 있으면 보관 종료가 기다려 그 작업을 보고,
  커밋된 보관 종료는 접수에서 보여 409 `EVENT-001` 이 된다(작업 접수는 행사 @Version 을 올리지 않으므로 낙관적 잠금으로는 못 막는다).
- 결과가 확정(DONE·FAILED)된 job 의 촬영 입력 asset 행을 지운다 — S3 객체는 이미 지웠다(지금은 행이 남는다).
  같은 합성 요청의 재생(멱등)이 보낸 입력 id 를 이 행으로 대조하므로, 재생 기간(`event.retention.capture-input-replay`, 기본 30일)이 지난 job 의 행만 지운다.
- 부스 기기·참가자 세션·결과 토큰 행은 행사 ARCHIVED 뒤 30일에 정리한다.

## 8. PortOne V2 웹훅 (P2B-7) — 실연동 미확인

- `POST /api/payments/webhook` 의 검증기를 Standard Webhooks 로 바꾼다: `webhook-id`·`webhook-timestamp`·`webhook-signature` 헤더,
  서명 = base64(HMAC-SHA256(secret, `${id}.${timestamp}.${rawBody}`)), 비밀은 `whsec_` 접두어 base64. 허용 시각차 5분.
  **원문 body 바이트**로 검증한다(JSON 재직렬화 금지). 같은 webhook-id 는 한 번만 처리(`webhook_receipt` 유니크).
- 서명이 맞으면 PortOne API 로 결제를 조회해(`GET /payments/{paymentId}?storeId=` — storeId 를 빼면 API 비밀의 상점으로 조회되므로 늘 보낸다,
  상점 id 가 없으면 기동하지 않는다) 금액·통화·상점·상태를 서버 주문과 대조한 뒤에만 상태를 바꾼다. 중복·역순 이벤트는 외부 결제 id 와 현재 상태로 거른다.
- 계약 결제는 `contract.paymentEvidence(kind PORTONE, reference = paymentId)` 로 연결한다. 개인 구독 결제 흐름은 그대로 둔다.
- 키(웹훅 비밀·API 비밀·상점 id)는 설정으로만 받는다. 키가 없으면 기존 Mock 검증기를 쓴다(운영 프로필에서는 기동 실패 — 지금 MockGatewayGuard 와 같은 방식).
  MockGatewayGuard 가 `payment.webhook.provider=mock` 도 함께 본다 — local·test 밖에서는 `PAYMENT_MOCK_ALLOWED` 없이 기동하지 않는다.
- 수신 기록은 `INSERT IGNORE` 로 남긴다. 같은 webhook-id 가 동시에 오면 늦은 쪽은 앞쪽 커밋을 기다렸다가 0행을 받고 200 으로 끝난다
  (유니크 위반을 예외로 받으면 트랜잭션이 롤백 전용이 되어 500 이 났다).
- 결제 확인 시각은 조건부 UPDATE(`paymentVerifiedAt IS NULL` 일 때만)로 쓴다 — 동시에 바뀐 계약 상태를 덮지 않고, 감사 로그도 한 번만.
- 웹훅이 증빙 등록보다 먼저 와서 확인되지 않은 계약은 ADMIN 이 `POST /api/admin/contracts/{contractId}/payment-verification` 으로 다시 확인한다
  (같은 대조, 맞으면 계약을 돌려준다. 증빙 없음·연동 꺼짐·불일치는 400 `GEN-002`).

## 9. 오류 코드

| 코드 | HTTP | 뜻 |
|---|---|---|
| MEMBER-001 | 410 | 초대가 만료됐다 |
| MEMBER-002 | 400 | 초대 토큰이 틀렸거나 이미 쓰였거나 회수됐다 |
| MEMBER-003 | 409 | 이미 멤버다 / 이미 같은 권한이 있다 |
| MEMBER-004 | 409 | 마지막 OWNER 는 내리거나 내보낼 수 없다 |
| PHOTO-001 | 403 | 이 행사의 해당 사진 권한이 없다(행사는 볼 수 있음) |
| EXPORT-001 | 409 | 묶음이 아직 준비되지 않았다 |
| EXPORT-002 | 410 | 묶음이 만료·무효다(권한 회수·사진 삭제 포함) |
| EXPORT-003 | 400 | 묶음 대상이 너무 많다(2,000장 초과) 또는 비었다 |
| ENT-001 | 403 | 적용할 이용권이 없다(게시 불가) |
| ENT-002 | 409 | 이용권의 합성 한도에 도달했다 |
| ENT-003 | 409 | 계약·이용권 상태 전이가 맞지 않다(CMS) |
| ENT-004 | 403 | 이용권에 그 기능이 없다(예: BULK_DOWNLOAD 없이 묶음 요청) |
| GEN-061 | 429 | 문의 접수 빈도 초과(기존 코드) |

## 10. 설정값

초대 7일, 사진함 limit 30/100, 썸네일·다운로드 GET 5분, ZIP 최대 2,000장·DONE 후 24시간·동시 1개,
문의 IP 분당 5회, 보관 종료 배치 04:00, ARCHIVED 후 30일 부가 행 정리, 촬영 입력 행은 확정 후 30일(재생 기간) 뒤 삭제.

## 11. 하지 않는 것

메일 발송(초대·문의 알림), 프로젝트·상시 캠페인·기업 총량(P3), attemptNo·실행별 출력(P2C), 원본 장기 보관 옵션,
다운로드 프록시(엄격한 즉시 회수), 출력 로고의 실제 합성 반영(P5 렌더 계약), 스토어 결제(P4), 실제 PortOne·S3 실연동 확인(키·계정 필요).
