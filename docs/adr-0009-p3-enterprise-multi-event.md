# ADR-0009 · P3 기업·다행사 — 프로젝트·상시 캠페인·권한 위임·여러 행사 묶음·기업 총량

- **상태**: 초안 2판 (프론트 의견 반영 — 응답 모양·권한 표시·알림·배분·묶음 경로·오류 코드 문구. 단계별로 합의 뒤 구현)
- **날짜**: 2026-10-26
- **관련**: R5 2·3·7·9·12·17·20·21절(O04), ADR-0007(P2A, §12), ADR-0008(P2B)
- **완료 조건(R5 20절)**: 여러 행사와 계약 고객 격리, 총량과 동시 운영 및 가용성 검증

## 0. 원칙

1. **단일 행사 고객에게 회사·프로젝트 구조를 앞세우지 않는다.** 프로젝트는 선택이다 — 프로젝트 없는 행사가 지금과 똑같이 동작해야 한다.
2. 상위(기업 → 프로젝트) 기본값은 **게시할 때 계산해 경험 발행본에 고정**한다. 상위를 고쳐도 운영 중 행사가 예고 없이 바뀌지 않는다(R5 2·6절).
3. 사진 권한은 여전히 행사 단위다. 위임은 「행사를 운영할 권한」을 나눠 주는 것이지 사진을 자동으로 여는 것이 아니다(ADR-0008 원칙 1 유지).
4. 기업 총량과 행사 배분량은 **한 트랜잭션, 잠금 순서 기업 → 행사**로 둘 다 원자적으로 검사·예약한다(R5 12절, O04).

## 1. 단계 나눔 (각각 별도 PR)

| 단계 | 내용 | migration |
|---|---|---|
| P3-1 | 프로젝트(공간 아래 선택 분류)·행사의 프로젝트 지정, 기업·프로젝트 브랜드 기본값과 상속·게시 고정·유효 설정 미리보기 | `project`, `event.project_id`, `workspace.brand_defaults`, `project.brand_defaults` |
| P3-2 | 권한 위임 — 공간 역할 OPERATOR, 행사·프로젝트 단위 운영 권한 | `project_membership`, `event_grant` 운영 권한 |
| P3-3 | 상시 캠페인(ALWAYS_ON) — 종료 시각 없음, 한도 알림, 자동 보관 종료 제외 | `event.kind`, `entitlement.notified_*` |
| P3-4 | 기업 총량·행사 배분 — 2단 예약 | `entitlement.parent_entitlement_id`, `usage_reservation` (작업, 이용권) 유니크로 변경 |
| P3-5 | 여러 행사 묶음 다운로드(프로젝트·공간 범위 ZIP) | `export_job.scope` |
| P3-6 | 격리 검수 묶음 — 다행사·다고객 교차 접근 시나리오 테스트(R5 21절) | 없음 |

인프라 쪽(무중단 배포·DB 운영 분리·가용성, R5 17절)은 AWS·운영 권한이 필요해 이 저장소의 코드 범위 밖이다 — 필요한 앱 쪽 준비(헬스 체크·종료 훅·읽기 복제 대비 읽기 전용 트랜잭션 구분)만 P3-6 에 함께 적는다.

## 2. 프로젝트와 브랜드 상속 (P3-1)

- `project(public_id, workspace_id, name, brand_defaults JSON, archived_at)` — 조직 공간에만. 행사의 `project_id` 는 선택.
- `brand_defaults` 는 행사 설정(EventSettings)의 **상속 가능한 필드만** 같은 모양으로 담는다(아래 표). 이름·slug·촬영 시각·kind 는 상속하지 않는다.
- **유효 설정 = 공간 기본값 ⊕ 프로젝트 기본값 ⊕ 행사 설정**(뒤가 이김, 아래 「경로」 단위). 게시 때 계산해 경험 발행본에 그대로 넣는다 —
  발행본은 지금처럼 자기 완결 문서다. 상위가 바뀌어도 이미 게시한 행사는 바뀌지 않고, 다시 게시해야 새 값이 들어간다.
- 프로젝트 보관(archive)은 새 행사 지정만 막고(409 `PROJECT-001`) 기존 행사는 그대로 둔다. 행사의 프로젝트는 게시 전 초안에서만 바꾼다(게시 뒤 409 `PROJECT-002`).
- API: `POST·GET /api/auth/workspaces/{workspaceId}/projects`, `PATCH /api/auth/projects/{projectId}`(이름·brandDefaults·archived),
  `GET·PATCH /api/auth/workspaces/{workspaceId}/brand-defaults`, 행사 설정의 `projectId`(PATCH 로 지정·해제).

### 2-1. 상속 경로와 값 지우기

| 경로(= sources 의 키) | 상속 | 비고 |
|---|---|---|
| `timezone`·`byodEnabled`·`boothEnabled`·`resultRetentionDays` | 예 | 최상위 값 |
| `allowedLayouts`·`frames`·`consentItems` | 예 | 목록은 **통째로** 대체(항목 단위로 섞지 않는다) |
| `theme.colors.bg` … `theme.colors.accentContrast`(7개) | 예 | 키마다 |
| `theme.copy.welcome` … `theme.copy.error`(8개) | 예 | 키마다 |
| `theme.font`·`theme.logoAssetId`·`theme.backgroundAssetId` | 예 | |
| `theme.branding.hideHarucutBranding`·`theme.branding.outputLogo` | 예 | WHITE_LABEL 검사는 유효값으로 |
| `name`·`slug`·`captureStartsAt`·`captureEndsAt`·`kind`·`projectId` | 아니오 | 늘 `EVENT` |

- 행사 설정 PATCH(ADR-0007 머지 패치)에서 **`null` = 직접 값을 지우고 상속으로 되돌린다.** 키를 보내지 않으면 그대로다.
- 상위 값을 덮어 「기본값·없음」으로 두고 싶을 때는 **빈 문자열 `""`** 을 직접 값으로 둔다. 뜻은 경로 종류마다 다르다:

| 경로 | `""` 의 뜻 | 유효 설정(settings) | sources |
|---|---|---|---|
| `theme.copy.*` | 상위 문구를 쓰지 않고 **FE 기본 문구** (문구를 「안 보이게」 하는 방법은 없다) | 키 없음 | `EVENT` |
| `theme.colors.*` | 상위 색을 쓰지 않고 **FE 기본색** | 키 없음 | `EVENT` |
| `theme.logoAssetId`·`theme.backgroundAssetId`·`theme.branding.outputLogo` | **이미지 없음** (예: 프로젝트 로고를 이 행사에서는 안 쓰기) | 키 없음 | `EVENT` |
| `theme.font` | 받지 않는다 — 400 `GEN-002`. 기본 글꼴은 값 `"SYSTEM"` 으로 둔다 | | |
| 그 밖의 문자열(`timezone`) | 받지 않는다 — 400 `GEN-002` | | |

  즉 settings 에서 키가 없을 때 sources 에 `EVENT` 가 있으면 「직접 기본값·없음으로 둠」, sources 에도 없으면 「어디에도 값 없음」이다 — 둘 다 FE 는 기본값을 쓴다.
  목록은 빈 목록 `[]` 이 직접 값이다(게시 검사에서 위반이면 그대로 위반). 불리언은 `true/false` 가 직접 값이다.
- 같은 규칙을 공간·프로젝트 brandDefaults PATCH 에도 쓴다(`null` = 그 단계의 값을 지운다).

### 2-2. 유효 설정 미리보기

`GET /api/auth/events/{eventId}/effective-settings` (그 행사의 `MANAGE_EVENT` 또는 공간 OWNER·ADMIN)

```json
{
  "settings": { /* EventSettings — 유효값. 빈 문자열 덮기는 키 없음으로 나온다 */ },
  "sources": { "theme.colors.bg": "PROJECT", "theme.copy.welcome": "EVENT", "consentItems": "WORKSPACE", "name": "EVENT" }
}
```

- `sources` 는 **값이 있는 경로(`""` 로 덮은 경로 포함)만** 담는다. 키가 없으면 어디에도 값이 없다는 뜻이다. 값은 `WORKSPACE`·`PROJECT`·`EVENT`.
- Studio 는 이 응답만으로 「상속값/직접 변경값」 표시와 「상속으로 되돌리기(= 그 경로에 null PATCH)」 버튼을 만든다.
- 게시는 이 계산과 같은 함수를 쓴다(미리보기와 발행본이 갈라지지 않게).

### 2-3. 구현에서 정한 것 (P3-1)

- 행사의 직접 값은 `event.settings_draft`(InheritableSettings JSON)에, 공간·프로젝트 기본값은 `brand_defaults` 에 같은 모양으로 둔다.
  V19 가 기존 행사의 시간대·참가 채널·보관 일수 컬럼 값을 초안 JSON 에 옮긴다 — 기존 행사는 모든 값이 직접 값이라 상위 기본값이 생겨도 바뀌지 않는다.
- 행사 컬럼(timezone·byod_enabled·booth_enabled·result_retention_days)은 계속 「지금 적용되는 값」이다(참가·보관 배치가 읽는다).
  만들 때·프로젝트를 바꿀 때·그 필드를 직접 PATCH 할 때·게시할 때만 유효값으로 다시 계산한다 — 다른 필드를 저장했다고 그사이 바뀐 상위 기본값이
  운영 중 행사에 끌려 들어오지 않는다.
- `GET /api/auth/events/{id}` 의 `settings` 는 유효값(지금 게시하면 들어갈 값)이다. 출처는 effective-settings 가 준다.
- 테마는 PATCH 에서 객체째 바꾼다(그 안에서 빠진 키·null = 상속). 공간·프로젝트 brandDefaults PATCH 는 키마다 머지 패치(테마는 객체째),
  상속할 수 없는 키는 400.
- 권한은 P3-1 에서 OWNER·ADMIN 만(OPERATOR 는 P3-2).

## 3. 권한 위임 (P3-2)

- 공간 역할에 `OPERATOR` 를 더한다 — 공간 전체 권한은 없고, 배정받은 행사·프로젝트만 운영한다(R5 3절 「담당 운영자」).
- 운영 권한(행사 단위, `OperationPermission`): `MANAGE_EVENT`(설정·게시·일시중지·촬영 종료·복제), `MANAGE_BOOTH`(기기 등록·재발급·회수),
  `INVITE_CUSTOMERS`(고객 초대·사진 권한 관리). EventGrant 에 사진 권한과 따로 둔다(한 행사에 대한 한 사람의 권한이 한 행).
- 프로젝트 단위: `project_membership(project, user, operations, photoPermissions)` — 그 프로젝트의 모든 행사(앞으로 만들 행사 포함)에 같은 권한.
  판정 시점에 합친다(행사 권한 ∪ 프로젝트 권한). 프로젝트에서 빠지면 상속 권한이 함께 사라진다.
- OWNER·ADMIN 은 지금처럼 공간 전체 운영. 판정은 `EventAccessPolicy` 한 곳(`can(event, user, permission)`).
- 위임을 줄 수 있는 사람: OWNER·ADMIN. OPERATOR 는 `INVITE_CUSTOMERS` 가 있을 때 **자기가 가진 사진 권한 범위 안에서** 고객 초대만.
- 계약·공간 사용량은 OWNER·ADMIN 만. OPERATOR 는 배정 행사의 사용량만.

### 3-1. FE 에 주는 권한 — `GET /api/auth/me/events`

`access` 를 넓힌다(기존 키는 그대로, 추가만):

```json
{ "eventId": "...", "name": "...", "status": "LIVE",
  "access": {
    "role": "OPERATOR",
    "permissions": ["VIEW_PHOTOS", "DOWNLOAD_PHOTOS"],
    "operations": ["MANAGE_EVENT", "MANAGE_BOOTH"],
    "sources": { "MANAGE_EVENT": "PROJECT", "MANAGE_BOOTH": "BOTH", "VIEW_PHOTOS": "EVENT" }
  } }
```

- `role`: 공간 역할(OWNER·ADMIN·OPERATOR). 행사 고객(공간 멤버 아님)이면 키 없음 — 지금과 같다.
- `operations`: 합쳐진 운영 권한. OWNER·ADMIN 은 셋 다, `sources` 키 없음(역할에서 온다).
- `sources`: 권한마다 `EVENT`(행사에서 직접)·`PROJECT`(프로젝트에서)·`BOTH`. `PROJECT`·`BOTH` 는 행사 화면에서 뺄 수 없다 — FE 가
  「프로젝트에서 받은 권한이라 여기서 못 뺀다」를 보여 준다.
- FE 는 버튼 표시를 이 값으로만 정한다. 서버는 같은 판정으로 막는다(범위 밖 404, 범위 안 권한 부족 403 `GEN-021`·사진은 `PHOTO-001`).

### 3-2. OPERATOR 가 보는 것

| 화면·API | OPERATOR |
|---|---|
| `GET /api/auth/workspaces`(내 공간) | 그 공간이 `role: OPERATOR` 로 보인다 |
| 공간 행사 목록 | 운영 권한(행사·프로젝트)이 하나라도 있는 행사만 |
| 공간 프로젝트 목록 | 멤버인 프로젝트만 |
| 공간 멤버·초대·brandDefaults·계약·공간 사용량 | 403 `GEN-021` |
| 공간 프레임 | 읽기만(행사 프레임 고르기용). 만들기·고치기 403 |
| `me/events` | 위 행사들 + 사진 권한을 받은 행사(지금과 같다) |

## 4. 상시 캠페인 (P3-3)

- `event.kind`: `SCHEDULED`(지금, 기본) · `ALWAYS_ON`. kind 는 게시 전 초안에서만 바꾼다.
- 계약 유효기간·합성 한도·보관 정책은 그대로 적용한다. 이용권이 끝나면 새 촬영이 막히고(ENT-002) 기존 결과는 보관 정책대로 남는다.
- 사람당 1회 같은 엄격한 제한은 하지 않는다(R5 7절) — 세션·기기·행사 한도와 빈도 제한이 기본.

| | SCHEDULED | ALWAYS_ON |
|---|---|---|
| Studio 폼 | 촬영 시작·종료 시각 입력 | kind 선택 시 종료 시각 입력을 비우고 숨긴다(시작은 선택) |
| 게시 검사(EVENT-003 위반 목록) | 기존 그대로(종료가 있으면 시작보다 뒤) | `captureEndsAt` 이 있으면 위반 `{field: "captureEndsAt", message: "상시 캠페인은 종료 시각을 두지 않는다"}` |
| 촬영 끝내기 | 종료 시각 도달 또는 close-capture | close-capture 만 |
| 자동 보관 종료(ADR-0008 §7) | 촬영이 끝났고 보일 결과가 없으면 | close-capture 뒤에만. 기한 지난 결과 정리는 똑같이 한다 |
| 한도 알림 | 공통 | 공통 |

### 4-1. 한도 알림

- 적용 이용권의 사용률(`used / composeQuota`, 예약 제외)이 80·95·100% 를 **처음** 넘는 순간(확정 트랜잭션 안) `entitlement.notified_80_at`·`_95_at`·`_100_at` 을 남기고 감사 로그를 쓴다.
- 사용량 응답에 `alerts: [{ "threshold": 80, "reachedAt": "2026-…+09:00" }, …]` — 넘은 것만, threshold 오름차순. 메일은 범위 밖.
- 한도 초과 처리는 P3 에서 「촬영 중단」(ENT-002)만 — 자동 과금·운영자 승인은 결제(P4) 이후 계약 옵션으로.

## 5. 기업 총량과 행사 배분 (P3-4)

- 이용권에 `parent_entitlement_id` — 행사 배분량(EVENT 범위)이 기업 총량(WORKSPACE 범위)의 자식이 된다. 자식의 유효기간·기능은 부모를 따른다.
- 같은 부모의 **활성** 자식 composeQuota 합은 부모 composeQuota 이하.
- 적용 판정: 행사에 자식 배분이 있으면 그것 + 부모, 없으면 지금처럼 하나.
- **예약**: 한 트랜잭션에서 부모(기업) → 자식(행사) 순서로 각각 `used + reserved + 1 <= quota` 조건부 UPDATE(유효기간 조건 포함).
  하나라도 0 행이면 ENT-002 로 전체 롤백 — 부모만 차감되고 자식은 실패하는 일이 없다. 잠금 순서가 고정이라 이 둘 사이 교착은 없고,
  다른 이유의 교착(재시도 가능 예외)은 접수 트랜잭션 전체를 제한적으로(3회) 다시 한다.
- `usage_reservation` 유니크를 `(compose_job_id, entitlement_id)` 로 바꾸고 작업당 범위마다 한 행. 확정·반환은 그 작업의 행 전부를
  같은 트랜잭션에서 조건부 UPDATE(RESERVED → COMMITTED | RELEASED). 원장 유니크는 `(compose_job_id, entitlement_id, kind)`.
- 참가 화면은 어느 쪽이 막혀도 `ENT-002` 하나(「오늘 촬영이 마감됐다」).
- 검수(O04): 기업 잔량 1·두 행사 각각 잔량 있음에서 동시 접수 2건 → 정확히 1건만 성공. MySQL 실제 동시성 테스트로 확인.

### 5-1. 사용량 응답

`GET /api/auth/events/{eventId}/usage` — 기존 키는 그대로(최상위 수치 = 이 행사에 적용되는 배분량, 배분이 없으면 지금처럼 그 이용권), 추가만:

```json
{ "entitlement": { … }, "composeQuota": 300, "used": 120, "reserved": 2, "remaining": 178,
  "retentionDays": 30, "maxBoothDevices": 5,
  "alerts": [{ "threshold": 80, "reachedAt": "…" }],
  "parent": { "composeQuota": 1000, "used": 950, "reserved": 3, "remaining": 47 } }
```

- `parent` 는 배분(자식)으로 적용될 때만, 그리고 OWNER·ADMIN 에게만 준다(OPERATOR·행사 고객에게는 기업 수치를 보이지 않는다 — 키 없음).
- 실제로 찍을 수 있는 양 = min(remaining, parent.remaining). FE 가 계산해 보여 준다.

### 5-2. CMS 배분 흐름(플랫폼 ADMIN)

1. 계약(WORKSPACE 범위)을 ACTIVE 로 → 부모 이용권(기존 흐름).
2. `POST /api/admin/entitlements/{parentId}/allocations` `{ eventId, composeQuota }` → 201 자식 이용권(Entitlement 응답 + `parentEntitlementId`).
   합이 부모를 넘으면 409 `ENT-005`, `data: { parentQuota, allocated, requested }`. 행사가 그 공간이 아니면 GEN-002.
3. `PATCH /api/admin/entitlements/{childId}` `{ composeQuota }` — 늘릴 때 같은 합 검사(ENT-005), 줄일 때 `used + reserved` 아래로는 409 `ENT-006`.
4. 자식 회수(기존 revoke) — 합에서 빠지고, 그 행사는 부모만으로 적용된다(배분 없는 행사와 같다).
5. 목록 `GET /api/admin/entitlements` 에 `parentEntitlementId`·`allocatedQuota`(부모일 때 활성 자식 합) 추가.
- 고객(OWNER)의 셀프 배분은 P3 범위 밖(계약 조건이라 플랫폼이 한다).

## 6. 여러 행사 묶음 다운로드 (P3-5)

행사 묶음(P2B-3, `/api/auth/events/{eventId}/exports`)과 **같은 모양**의 경로·응답을 범위만 바꿔 둔다:

| | 프로젝트 | 공간 |
|---|---|---|
| 만들기 | `POST /api/auth/projects/{projectId}/exports` | `POST /api/auth/workspaces/{workspaceId}/exports` |
| 목록 | `GET …/exports` | `GET …/exports` |
| 상태 | `GET …/exports/{exportId}` | 같음 |
| 받기 | `POST …/exports/{exportId}/download` | 같음 |

- 요청 `{ "eventIds": [...] }`(필수, 1개 이상 — 무엇을 담는지 요청에 명시). 응답은 행사 Export 와 같은 스키마 + `events: [{ eventId, photoCount }]`.
- 담기는 사진 = 각 행사에서 그 사람이 `BULK_DOWNLOAD` 를 가진 행사의 보이는 사진. 하나라도 권한이 없으면 조용히 빼지 않고 403 `PHOTO-001`
  `data: { eventIds: [권한 없는 행사] }`, 이용권 기능 BULK_DOWNLOAD 가 없는 행사가 있으면 403 `ENT-004` 같은 data.
- ZIP 안은 `{행사 slug}/0001-{photoId}.jpg`. 최대 장수(2,000)·수명·동시 1개·재검사·오류 코드(EXPORT-001~003)는 P2B-3 과 같다.
- 누가: 프로젝트는 OWNER·ADMIN·프로젝트 멤버, 공간은 OWNER·ADMIN.

## 7. 오류 코드 (추가 예정)

FE 계약 검사가 「서버가 내는데 FE 문구 없음」으로 실패하므로, **코드를 develop 에 넣는 PR 을 열 때 코드·문구 초안을 함께 보낸다.** FE 가 문구를
먼저(또는 같은 날) develop 에 넣는다. 아래는 문구 초안이다(FE 가 다듬는다).

| 코드 | HTTP | 뜻 | 들어가는 단계 | FE 문구 초안 |
|---|---|---|---|---|
| PROJECT-001 | 409 | 보관된 프로젝트에는 행사를 지정할 수 없다 | P3-1 | 보관된 프로젝트에는 행사를 넣을 수 없어요. |
| PROJECT-002 | 409 | 게시한 행사의 프로젝트는 바꿀 수 없다 | P3-1 | 게시한 행사는 프로젝트를 바꿀 수 없어요. |
| ENT-005 | 409 | 행사 배분 합이 기업 총량을 넘는다 | P3-4 | 배분 합계가 계약 총량을 넘어요. 남은 양: {parentQuota − allocated} |
| ENT-006 | 409 | 배분량을 이미 사용·예약한 양보다 줄일 수 없다 | P3-4 | 이미 사용한 양보다 적게 줄일 수 없어요. |

권한 부족은 지금처럼 범위 밖 404·범위 안 403(`PHOTO-001` 또는 운영 권한이면 `GEN-021`). ALWAYS_ON 종료 시각은 게시 위반(EVENT-003 목록)이라 새 코드가 없다.

## 7-1. 진행 방식

- 단계마다 develop 기준 브랜치 하나, 깊이 쌓지 않는다. 하나가 머지되면 다음 단계(P3-1 → P3-2 → … → P3-6).
- 각 PR: 계약(OpenAPI 생성본)·Flyway migration·운영 스키마 확인 표시·「프론트 영향」 표(새 코드·문구 초안 포함).

## 8. P2C 와의 관계

P2C(공개 행사 준비 — 부하·Lambda·Redis·배포 통제·모니터링·복구, R5 20절)는 대부분 AWS·운영 측정이라 사용자 권한이 필요하다.
코드 쪽 남은 항목인 attemptNo·실행별 출력 위치·승자 결과 참조(R5 13절)는 Lambda 변경·배포가 함께 가야 해서 별도 ADR(0010)로 다룬다 —
배포 순서(소비자 → Lambda → 발행 활성화)를 지킬 수 있을 때 진행한다.

## 9. 하지 않는 것

메일 알림, 자동 과금·초과 승인 흐름(P4 이후), 화이트라벨 도메인, 프로젝트 간 이동(행사의 프로젝트 변경은 게시 전 초안에서만),
기업 SSO, 인프라 무중단 배포·DB 분리 실행(사용자·AWS 권한).
