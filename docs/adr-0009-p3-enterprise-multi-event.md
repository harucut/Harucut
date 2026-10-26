# ADR-0009 · P3 기업·다행사 — 프로젝트·상시 캠페인·권한 위임·여러 행사 묶음·기업 총량

- **상태**: 초안 (프론트 합의 전, 구현하지 않음 — P2B 가 develop 에 들어간 뒤 합의·구현)
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
- `brand_defaults` 는 PageTheme(ADR-0008 §5)의 부분 집합과 같은 모양 + 기본 동의 항목·기본 허용 레이아웃·기본 제공 프레임 후보.
- **유효 설정 = 공간 기본값 ⊕ 프로젝트 기본값 ⊕ 행사 설정**(뒤가 이김, 필드 단위). 게시 때 계산해 경험 발행본에 그대로 넣는다 —
  발행본은 지금처럼 자기 완결 문서다. 상위가 바뀌어도 이미 게시한 행사는 바뀌지 않고, 다시 게시해야 새 값이 들어간다.
- `GET /api/auth/events/{eventId}/effective-settings` — 게시 전 미리보기. 필드마다 출처(`WORKSPACE`·`PROJECT`·`EVENT`)를 함께 준다 —
  Studio 가 「상속값/직접 변경값」을 구분해 보여 줄 수 있게(R5 9절).
- API: `POST·GET /api/auth/workspaces/{workspaceId}/projects`, `PATCH /api/auth/projects/{projectId}`(이름·brandDefaults),
  `PATCH /api/auth/workspaces/{workspaceId}/brand-defaults`, 행사 설정의 `projectId`.
- 프로젝트 보관(archive)은 새 행사 지정만 막고 기존 행사는 그대로 둔다.

## 3. 권한 위임 (P3-2)

- 공간 역할에 `OPERATOR` 를 더한다 — 공간 전체 권한은 없고, 배정받은 행사·프로젝트만 운영한다(R5 3절 「담당 운영자」).
- 운영 권한(행사 단위): `MANAGE_EVENT`(설정·게시·일시중지·촬영 종료·복제), `MANAGE_BOOTH`(기기 등록·재발급·회수), `INVITE_CUSTOMERS`(고객 초대·사진 권한 관리).
  EventGrant 의 권한 집합에 사진 권한과 함께 둔다(한 행사에 대한 한 사람의 권한이 한 행).
- 프로젝트 단위: `project_membership(project, user, permissions)` — 그 프로젝트의 모든 행사(앞으로 만들 행사 포함)에 같은 권한을 준다.
  판정 시점에 합친다(행사 권한 ∪ 프로젝트 권한). 프로젝트에서 빠지면 그 프로젝트 행사의 상속 권한이 함께 사라진다.
- OWNER·ADMIN 은 지금처럼 공간 전체 운영. 판정은 `EventAccessPolicy` 한 곳에 계속 둔다(`isOperator` → `can(event, user, permission)`).
- 위임을 줄 수 있는 사람: OWNER·ADMIN. OPERATOR 는 자기가 가진 권한 범위 안에서 고객 초대만(`INVITE_CUSTOMERS` 가 있을 때).
- 계약·사용량(기업 전체)은 OWNER·ADMIN 만 본다. OPERATOR 는 배정 행사의 사용량만.

## 4. 상시 캠페인 (P3-3)

- `event.kind`: `SCHEDULED`(지금) · `ALWAYS_ON`. ALWAYS_ON 은 촬영 종료 시각을 비울 수 있다.
- 계약 유효기간·합성 한도·보관 정책은 그대로 적용한다. 이용권이 끝나면 새 촬영이 막히고(ENT-002) 기존 결과는 보관 정책대로 남는다.
- 보관 종료 배치(ADR-0008 §7)는 ALWAYS_ON 을 자동 ARCHIVED 로 만들지 않는다 — 운영자가 촬영 종료해야 대상이 된다. 기한 지난 결과 정리는 똑같이 한다.
- 한도 알림: 이용권 사용률이 80%·95%·100% 를 처음 넘을 때 감사 로그와 사용량 응답의 `alerts[]` 에 남긴다(메일은 범위 밖).
  한도 초과 처리는 P3 에서 「촬영 중단」만 — 자동 과금·운영자 승인은 결제(P4) 이후 계약 옵션으로.
- 사람당 1회 같은 엄격한 제한은 하지 않는다(R5 7절) — 세션·기기·행사 한도와 빈도 제한이 기본.

## 5. 기업 총량과 행사 배분 (P3-4)

- 이용권에 `parent_entitlement_id` — 행사 배분량(EVENT 범위)이 기업 총량(WORKSPACE 범위)의 자식이 된다.
  같은 부모의 자식 compose_quota 합은 부모 quota 이하(부여·수정 때 검사, 넘으면 409 `ENT-005`).
- 적용 판정: 행사에 자식 배분이 있으면 그것 + 부모, 없으면 지금처럼 하나.
- **예약**: 한 트랜잭션에서 부모(기업) → 자식(행사) 순서로 각각 `used + reserved + 1 <= quota` 조건부 UPDATE.
  하나라도 0 행이면 ENT-002 로 전체 롤백 — 부모만 차감되고 자식은 실패하는 일이 없다. 잠금 순서가 고정이라 교착이 생기지 않고,
  충돌(재시도 가능 예외)은 전체를 제한적으로(3회) 다시 시도한다.
- `usage_reservation` 의 유니크를 `(compose_job_id, entitlement_id)` 로 바꾸고 작업당 범위마다 한 행. 확정·반환은 그 작업의 행 전부를
  같은 조건부 UPDATE(RESERVED → COMMITTED | RELEASED)로 — 한 범위만 확정되는 일이 없게 같은 트랜잭션에서.
- 원장 유니크도 `(compose_job_id, entitlement_id, kind)` 로(R5 12절: 예산별 기록이면 (job, quota_scope, kind)).
- 검수(O04): 기업 잔량 1·두 행사 각각 잔량 있음에서 동시 접수 2건 → 정확히 1건만 성공. MySQL 실제 동시성 테스트로 확인.

## 6. 여러 행사 묶음 다운로드 (P3-5)

- `POST /api/auth/projects/{projectId}/exports`, `POST /api/auth/workspaces/{workspaceId}/exports` — 대상 행사 각각에 BULK_DOWNLOAD 권한이 있는
  행사의 사진만 담는다(권한 없는 행사는 조용히 빼지 않고 403 — 무엇이 빠졌는지 알 수 없게 되지 않도록, 요청에 행사 목록을 명시).
- ZIP 안은 `{행사 slug}/0001-{photoId}.jpg`. 최대 장수·수명·재검사는 P2B-3 과 같다. 계약 기능 BULK_DOWNLOAD 는 행사마다 본다.

## 7. 오류 코드 (추가 예정)

| 코드 | HTTP | 뜻 |
|---|---|---|
| ENT-005 | 409 | 행사 배분 합이 기업 총량을 넘는다 |
| PROJECT-001 | 409 | 보관된 프로젝트에는 행사를 지정할 수 없다 |

권한 부족은 지금처럼 범위 밖 404·범위 안 403(`PHOTO-001` 또는 운영 권한이면 `GEN-021`).

## 8. P2C 와의 관계

P2C(공개 행사 준비 — 부하·Lambda·Redis·배포 통제·모니터링·복구, R5 20절)는 대부분 AWS·운영 측정이라 사용자 권한이 필요하다.
코드 쪽 남은 항목인 attemptNo·실행별 출력 위치·승자 결과 참조(R5 13절)는 Lambda 변경·배포가 함께 가야 해서 별도 ADR(0010)로 다룬다 —
배포 순서(소비자 → Lambda → 발행 활성화)를 지킬 수 있을 때 진행한다.

## 9. 하지 않는 것

메일 알림, 자동 과금·초과 승인 흐름(P4 이후), 화이트라벨 도메인, 프로젝트 간 이동(행사의 프로젝트 변경은 게시 전 초안에서만),
기업 SSO, 인프라 무중단 배포·DB 분리 실행(사용자·AWS 권한).
