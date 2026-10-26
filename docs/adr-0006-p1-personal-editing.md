# ADR-0006 · P1 개인·공통 편집 — Workspace·Asset·FrameVersion·서버 초안 계약

- **상태**: 합의됨 (프론트 의견 반영 2026-10-26). 바뀌면 이 문서를 먼저 고친다
- **날짜**: 2026-10-26
- **관련**: R5 기획서 5·6·14·16·20절, `docs/api/p1-openapi-draft.yaml`, ADR-0005(Flyway)

## 0. 원칙

1. **기존 요청·응답은 깨지 않는다.** 새 필드는 전부 선택(optional)이고, 응답 필드는 추가만 한다.
   기존 경로(`/api/auth/user/frame`, `/api/auth/user/files/presigned-upload`)는 그대로 동작한다.
2. **경로 규칙은 기존 것을 따른다**(R5 15절): 로그인 고객 API 는 `/api/auth/...`, 관리자는 `/api/admin/...`.
3. **인증 범주는 전부 "회원"**(cookieAuth / bearerAuth). 참가자·부스·결과 범주는 P2A.
4. **스키마 변경은 Flyway V2+** 로 쓴다(ADR-0005). 운영 편입 전이라 운영에는 update 가 같은 변경을 만든다.
5. 렌더 결과가 바뀌는 변경(배경 fit 등)은 **서버·Lambda 동시 배포가 전제**다. Lambda 배포는 사용자 승인 대상이라,
   승인 전에는 새 값을 저장·미리보기까지만 쓰고 합성은 기존 동작(COVER·중앙)과 같다.

## 1. Workspace (최소 모델)

- 테이블 `workspace`(public_id, type=`PERSONAL`|`ORGANIZATION`, name, owner_user_id) +
  `workspace_membership`(workspace_id, user_id, role=`OWNER`|`ADMIN`|`DESIGNER`|`VIEWER`, status).
- P1 에서는 **개인 Workspace 만** 쓴다. 사용자당 하나, 처음 필요할 때 만든다(지연 생성). 기존 사용자는 V2 migration 이 백필한다.
- 프레임·자산·초안은 `workspace_id` 를 가진다. 기존 `frame.user_id` 는 그대로 두고 함께 채운다(호환 기간).
  시스템 프레임은 `workspace_id = NULL` + `is_system = true` 로 SYSTEM 범위다 — 유령 워크스페이스를 만들지 않는다.
- 권한 판정은 DB 관계(membership)로 한다. S3 key 접두사는 정리 편의일 뿐 권한 근거가 아니다(R5 6절).
- API: `GET /api/auth/workspaces` — 접근 가능한 공간 목록. P1 에서는 개인 공간 하나가 나온다.

## 2. Asset — 공통 이미지 자산

- 테이블 `asset`: public_id, workspace_id(NULL=SYSTEM), purpose, origin, s3_key(unique), content_type,
  declared_size, actual_size, width, height, status, uploaded_by_user_id, verified_at.
- purpose: `FRAME_ASSET`(배경·PHOTO·STICKER·로고), `TEXT_RENDER`(제작자 브라우저가 구운 글자 PNG), `PAGE_THEME_ASSET`(P2B),
  `PROFILE`. 형식: FRAME_ASSET PNG·JPEG, TEXT_RENDER PNG, PROFILE PNG·JPEG·GIF. **WEBP 는 받지 않는다**(합성기 ImageIO 가 못 읽고,
  헤더만으로 검증 완료라 표시하지 않는다는 원칙 때문). 맞지 않으면 415 `GEN-051`. `CAPTURE_INPUT` 은 P2A 참가자 경로 전용이라 여기서 받지 않는다(개인 합성 원본은 기존 FOURCUT_SOURCE presign 유지).
- origin: `CREATOR_BROWSER` | `SERVER`. P1 업로드는 전부 CREATOR_BROWSER.
- status: `PENDING_UPLOAD` → (`complete`) → `VERIFIED` | `REJECTED`. **VERIFIED 가 아닌 자산은 프레임·합성에 쓸 수 없다.**
- 흐름:
  1. `POST /api/auth/assets/upload-intents` → assetId + presigned PUT URL + **반드시 그대로 보낼 헤더**(`Content-Type`, `If-None-Match: *`) + 만료 시각.
     PUT URL 기본 TTL 10분(`storage.upload.put-ttl`). 서버가 key 를 정한다 — `uploads/users/{publicId}/assets/{assetId}.{ext}`.
     조건부 PUT 스위치 `storage.upload.conditional-put`(기본 true): 끄면 `If-None-Match` 를 서명에도 응답 headers 에도 넣지 않는다.
     운영 버킷 CORS 의 AllowedHeaders 에 If-None-Match 가 있는지 확인하기 전에는 끈 채로 배포할 수 있다. 프론트는 응답 headers 를 그대로 보낸다.
  2. 브라우저가 S3 에 PUT. 이미 객체가 있으면 S3 가 412 — 같은 intent 의 재시도면 `complete` 를 불러 확인하면 된다.
  3. `POST /api/auth/assets/{assetId}/complete` → 서버가 HeadObject(존재·크기) → 바이트 머리로 실제 형식 판별 →
     ImageReader 로 크기(픽셀 상한) → **서브샘플링 디코드로 끝까지 읽히는지** 확인 → VERIFIED.
     디코드는 동시 실행 수를 제한한다(`storage.asset.verify-concurrency`, 기본 2) — 908MB 서버에서 원본 해상도로 풀지 않는다.
  4. `GET /api/auth/assets/{assetId}` → 메타데이터 + 짧은 GET URL(기본 5분, `storage.download.get-ttl`).
- 기존 key 문자열 경로(호환): 프레임 저장은 계속 `source`/`key` 문자열을 받는다. 같은 자리에 `assetId` 를 보내면 그쪽이 우선한다.
  기존 uploads/ key 를 일괄 이동하지 않는다.
- **참조와 정리**: 자산의 "참조"는 프레임(현재 상태)·발행본·서버 초안(`assetIds`, §5) 세 곳이다.
  어디서도 참조하지 않는 VERIFIED·REJECTED 자산과 PENDING_UPLOAD 로 남은 자산은 생성 후 `storage.asset.orphan-after`(기본 7일)
  지나면 정리 배치가 S3 객체와 행을 지운다(매일 03:00). 참조 판정은 DB 관계로만 한다.
  P1 에서 새로 생긴 자산만 대상이다 — 기존 key 문자열(assetId 없는 프레임 자산)은 지금처럼 프레임 수정·삭제 때 정리한다.

## 3. 프레임 저장 확장 (기존 POST/PUT `/api/auth/user/frame`)

추가 필드(모두 선택):
| 위치 | 필드 | 의미 |
|---|---|---|
| 요청 본문 | `version` | 낙관적 잠금. PUT 에서 보내면 현재 값과 다를 때 409 `FRAME-002`. 안 보내면 지금처럼 덮어쓴다(호환) |
| component | `assetId` | 검증된 자산 참조. 있으면 `source` 대신 이것으로 key 를 해결 |
| component(TEXT) | `renderedAssetId` | 구운 글자 PNG 자산. 있으면 `renderedKey` 대신 |
| component | `locked` | 편집기 잠금 상태 저장·재열기용. **인가 근거가 아니다** |
| background IMAGE | `assetId` | 배경 자산 |
| background IMAGE | `fit` | `COVER`(기본, 현재 동작) \| `CONTAIN` |
| background IMAGE | `focalX`, `focalY` | 0~1, 기본 0.5 — COVER 로 잘릴 때 남길 중심 |
| background IMAGE | `crop` | `{x,y,width,height}` 0~1 정규화 원본 영역. 없으면 전체 |

응답 추가 필드:
| 위치 | 필드 | 의미 |
|---|---|---|
| 프레임 | `version` | 다음 PUT 에 그대로 보낸다 |
| component | `locked`, `assetId`, `renderedAssetId` | 저장한 값 |
| component(TEXT) | `renderedKey`, `renderedUrl` | 지금은 응답에 없어 재저장마다 다시 굽는다 — 이제 돌려준다 |
| component(STICKER) | `source`, `key` | **uploads/ key 면 PHOTO 와 같이 `source` = presigned GET URL**, 정적 경로면 그대로. **`key` 는 항상 채운다** — 정적 경로면 `key` = `source` |

> 프론트 합의: 이미지 레이어는 응답 `key` 를 재저장 `source` 로 되돌리고 URL 은 그리기 전용으로 쓴다(harucut_FE lib/frameApi.ts).
> 그래서 STICKER `key` 가 비면 URL 이 source 로 남아 재저장 때 다시 올라간다 — 서버가 항상 채운다.

검증(저장 시점, 4xx):
- 레이어 수 상한 `frame.limits.max-layers`(기본 40) → 400 `FRAME-003`.
- 직렬화한 합성 spec 상한 `frame.limits.max-spec-bytes`(기본 7000, 현 spec_json 8000 컬럼 안) → 400 `FRAME-004`.
- `assetId` 가 남의 공간·미검증·용도 불일치 → 400 `FRAME-005`. PHOTO·STICKER·배경은 FRAME_ASSET,
  TEXT 의 `renderedAssetId` 는 **이 사용자 공간의 TEXT_RENDER** 여야 한다(`renderedKey` 로 보냈어도 그 key 가 TEXT_RENDER 자산이면
  같은 검사를 한다). PNG 내용이 글자와 일치하는지는 서버가 검증하지 않는다.

정리:
- STICKER 의 uploads/ key 도 프레임 수정·삭제 시 참조를 잃으면 삭제 후보에 넣는다(지금은 빠져 있다).
  단 발행 버전(§4)이 참조하는 key 는 지우지 않는다.

## 4. FrameVersion — 불변 발행본

- 테이블 `frame_version`: frame_id, version_no(프레임별 1부터), spec_json(TEXT, FrameSpec v2), created_by_user_id, created_at. **수정 API 없음.**
- `POST /api/auth/user/frame/{frameId}/versions` — 현재 프레임 상태를 검증해 새 발행본을 만든다.
  검증: 모든 자산 VERIFIED·소유 범위 일치, TEXT 는 구운 PNG 필수, 레이어·크기 상한.
- `GET /api/auth/user/frame/{frameId}/versions`, `GET .../versions/{versionNo}`.
- 관리자(시스템 프레임): `POST /api/admin/frames/{frameId}/versions` 외 동일.
- FrameSpec v2(R5 6절 표): schemaVersion=2, canvas, layout(frameType+고정 4슬롯), slotBindings(slotId·sourceIndex),
  layers(role·geometry·assetId·text 메타), editPolicy(P2A 에서 사용, P1 은 `{ "mode": "FIXED" }`).
  compose-core 가 직접 읽는 형식이 아니다 — 서버 어댑터가 기존 ComposeSpec 으로 바꾼다.
- 개인 합성(`POST /api/auth/user/media/compose`)은 P1 에서도 지금처럼 프레임의 현재 상태로 합성한다.
  행사 합성(P2A)은 발행본만 쓴다.

## 5. 서버 초안 — 편집기 문서 저장

- 테이블 `frame_draft`: public_id, workspace_id, user_id, frame_id(NULL=새 프레임), document(JSON/TEXT), revision, updated_at.
- 서버는 `document` 를 해석하지 않는다(편집기 상태 그대로, 상한 `frame.draft.max-bytes` 기본 256KB). 합성에 쓰지 않는다.
- 초안 저장 요청은 `document` 와 함께 **`assetIds: []`**(document 가 쓰는 자산 id 목록)를 받는다. 서버는 document 를 해석하지 않으므로
  이 목록이 자산 참조(§2 정리 판정)의 근거다. 목록의 자산은 이 공간의 VERIFIED 자산이어야 한다(아니면 400 `FRAME-005`).
  사진·배경을 dataURL 로 document 에 넣지 않는다 — 먼저 업로드(intent→complete)하고 assetId 만 넣는다.
- `POST /api/auth/user/frame-drafts` → 201 `{draftId, revision: 1}`
- `GET /api/auth/user/frame-drafts?frameId={id|new}` / `GET /api/auth/user/frame-drafts/{draftId}`
- `PUT /api/auth/user/frame-drafts/{draftId}` 본문 `{revision, document, assetIds}` → revision 이 서버와 다르면 409 `DRAFT-001`
  (응답에 서버의 현재 revision·updatedAt). 맞으면 revision+1.
- `DELETE /api/auth/user/frame-drafts/{draftId}`
- undo/redo 는 편집기 안의 일이라 서버 계약이 없다. 초안에는 현재 문서만 저장한다.
- 일반 게스트는 서버 초안 경로를 쓰지 않는다(로그인 필수 경로).

## 6. 새 오류 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| ASSET-001 | 409 | complete 시점에 S3 객체가 없다(아직 안 올렸거나 실패) |
| ASSET-002 | 422 | 실제 크기가 선언과 다르거나 상한 초과 |
| ASSET-003 | 422 | 실제 형식이 선언과 다르거나 지원하지 않는 형식·디코드 실패 |
| ASSET-004 | 422 | 픽셀 수 상한 초과(`storage.asset.max-pixels`, 기본 40,000,000) |
| ASSET-005 | 400 | 이 경로에서 받지 않는 purpose(CAPTURE_INPUT 등) |
| ASSET-006 | 503 | 디코드 검증이 밀려 있다 — 잠시 뒤 같은 complete 를 다시 부른다(동시 검증 기본 2개, 대기 20초) |
| FRAME-002 | 409 | 프레임 version 불일치(다른 기기에서 먼저 저장) |
| FRAME-003 | 400 | 레이어 수 상한 초과 |
| FRAME-004 | 400 | 합성 spec 크기 상한 초과 |
| FRAME-005 | 400 | 쓸 수 없는 assetId(남의 것·미검증·용도 불일치) |
| DRAFT-001 | 409 | 초안 revision 불일치 |
| DRAFT-002 | 413 | 초안 문서 크기 상한 초과 |

없는 자산·초안·버전, 남의 것은 기존처럼 404 `GEN-031` 이다(존재를 알리지 않는다).

## 7. DB migration (V2)

`workspace`, `workspace_membership`, `asset`, `frame_version`, `frame_draft` 생성.
`frame` 에 `workspace_id`, `version`(낙관적 잠금, 기존 행 0) 추가. `frame_component` 에 `locked`, `asset_id`, `rendered_asset_id` 추가.
기존 사용자마다 개인 workspace·OWNER membership 백필, 사용자 프레임의 `workspace_id` 백필.
⚠️ 운영 배포 전 운영 스키마 대조 필요(ADR-0005 §3).

## 8. 하지 않는 것 (P1 범위 밖)

- 참가자·부스·결과·행사(P2A), 계약·이용권(P2B), 자유 사진 칸(P5).
- 서버 한글 텍스트 렌더(P2A 참가자 자유 문구 전제).
- Lambda 배포(사용자 승인 필요) — §0-5.
