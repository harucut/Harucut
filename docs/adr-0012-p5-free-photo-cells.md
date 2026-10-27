# ADR-0012 · P5 자유 사진 칸 — 네 칸의 위치·크기·회전·모양과 입력 순서

- **상태**: 1판 — §1~§3 계약(P5 계약 PR), §4 렌더(다음 PR)
- **날짜**: 2026-10-27
- **관련**: R5 6절(검수 절 「네 칸을 유지하는 자유 슬롯의 좌표·마스크·입력 순서와 구형 spec 호환을 별도 검증한다. 가변 컷수는 필요가 확정될 때만」),
  ADR-0006 §4(FrameSpec v2)

## 0. 원칙

1. **칸은 네 개를 유지한다.** 칸 수가 바뀌면 입력·DB 제약(합성 작업의 원본 4개)까지 바뀌므로 P5 범위가 아니다.
2. **구형 프레임은 그대로다.** 칸이 없으면(null) `frameType` 의 고정 레이아웃이고, 자유 칸은 명시적인 `cells` 가 있을 때만이다.
3. 칸별 필터는 없다(R5 에 없다).
4. 렌더가 준비되기 전에는 자유 칸 프레임으로 합성하지 않는다 — 옛 렌더러는 모르는 필드를 무시하고 사각 칸으로 그려 버린다.

## 1. 칸 모델

| 필드 | 뜻 | 검증 |
|---|---|---|
| `x`·`y`·`width`·`height` | 회전 전 사각형(캔버스 px, 정수) | 캔버스(frameType 레이아웃) 안, 너비·높이 100px 이상 |
| `rotation` | 칸 중심 기준 회전(도, 시계 방향) | -180~180 |
| `shape.type` | `RECT` · `ELLIPSE` · `MASK` | MASK 는 아직 400(자리만 잡는다) |
| `shape.cornerRadius` | RECT 둥근 모서리 px | 0~min(width,height)/2, ELLIPSE·MASK 는 없음 |
| `shape.maskAssetId` | MASK 의 마스크 자산 | 지금은 400 |
| `shotIndex` | 이 칸에 넣을 촬영 순번 | 0~3, 네 칸에서 서로 다름 |

- 정확히 4칸. 위반은 모아서 한 번에 400 `GEN-002` + `data.violations: [{field, reason}]`.
- 저장: `frame.photo_cells`(JSON, V25). 프레임 생성·수정(`POST`·`PUT /api/auth/user/frame`, 관리자 프레임도 같은 조립)에서 `cells` 로 받고,
  응답 `FrameResponse.cells`(자유 칸일 때만 키). `PUT` 은 전체 교체라 `cells` 를 빼면 고정 레이아웃으로 돌아간다.
- 초안(document)은 서버가 해석하지 않으므로 바뀌지 않는다.

## 2. 발행본(FrameSpec v2) — 필드 추가만, 스키마 버전 2 그대로

- `layout.cells: [{slotId, x, y, width, height, rotation, shape, shotIndex}]` — 자유 칸일 때만.
- `layout.slots` 는 늘 채운다. 자유 칸이면 각 칸의 회전 전 사각형(s1~s4 = cells 순서) — 옛 화면은 사각 미리보기로라도 읽는다.
- `slotBindings[].sourceIndex` = 그 칸의 `shotIndex`(고정 레이아웃은 0~3 그대로).

## 3. 렌더 스위치

- `compose.free-cells.enabled`(기본 false). 꺼져 있으면 자유 칸 프레임으로 ① 행사 게시(그 발행본을 쓰는 행사) ② 개인 합성
  ③ 참가자 합성이 409 `FRAME-008`. 프레임 저장·초안·발행본 만들기·미리보기는 된다.
- 배포 순서: 렌더(§4)를 담은 합성 Lambda 배포 → 스위치 on.

## 4. 렌더(다음 PR)

compose-core(서버 실행기·Lambda 공용)에 칸 회전·모양 클립(RECT 둥근 모서리·ELLIPSE)과 shotIndex 입력 순서를 넣는다. 합성 작업 스냅샷(ComposeSpec)에
칸 정보를 싣고, 옛 스냅샷(칸 정보 없음)은 지금처럼 그린다. 서버 미리보기 렌더는 지금 없다(미리보기 PNG 는 프론트가 만들어 올린다).
