# 로컬에서 행사 게시까지 — 이용권 시드 절차

P2B-5 부터 행사 **게시에 이용권이 필요**하다(없으면 403 `ENT-001`). 로컬(docker compose, AWS 없이)에서 게시·촬영까지 확인할 때
아래 순서로 이용권을 준다.

> ⚠️ **로컬 DB 전용 절차다.** develop·운영 서버 DB 에는 쓰지 않는다. 시작 전에 `.env` 의 `DB_URL` 이
> `localhost`(또는 `127.0.0.1`) 의 로컬 컨테이너를 가리키는지 확인한다. 원격 주소면 멈춘다.

## 1. 로컬 기반 띄우기

```bash
docker compose up -d mysql redis mailpit
grep '^DB_URL' .env        # jdbc:mysql://localhost:<포트>/... 인지 확인
./gradlew bootRun
```

Flyway 를 켜서 띄우면(`FLYWAY_ENABLED=true`) V1~최신 migration 이 로컬 DB 에 적용된다.

## 2. 플랫폼 ADMIN 계정 만들기 (로컬)

1. 보통 회원가입으로 계정을 하나 만든다 — 이메일 인증 코드는 mailpit(http://localhost:8025)에서 읽는다.
2. 그 계정을 **로컬 DB 에서만** ADMIN 으로 올린다.

   ```bash
   source .env
   docker exec -i harucut-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" \
     -e "UPDATE users SET user_role='ROLE_ADMIN' WHERE email='admin@local.test';"
   ```

3. 다시 로그인한다(새 액세스 토큰).

## 3. 조직 공간 만들기 (운영자 계정)

운영자 계정(ADMIN 계정이어도 된다)으로:

```http
POST /api/auth/workspaces
{ "name": "로컬 웨딩홀" }
```

응답의 `data.workspaceId` 를 적어 둔다.

## 4. 수동 이용권 주기 (ADMIN 계정)

```http
POST /api/admin/entitlements
{
  "workspaceId": "<3번의 workspaceId>",
  "validFrom": "2026-01-01T00:00:00+09:00",
  "validTo": "2027-12-31T23:59:59+09:00",
  "terms": {
    "composeQuota": 1000,
    "retentionDays": 90,
    "maxBoothDevices": 5,
    "maxLiveEvents": 3,
    "features": ["WHITE_LABEL", "CUSTOM_THEME", "BULK_DOWNLOAD"]
  },
  "grantorNote": "로컬 개발용"
}
```

- `eventId` 를 넣으면 그 행사 범위, 빼면 공간 범위(그 공간의 모든 행사에 적용).
- `retentionDays`·`maxBoothDevices`·`maxLiveEvents` 는 빼면 상한 없음(부스 수는 설정값 20).
- `features` 를 빼면: 테마 디자인(`CUSTOM_THEME`)·하루컷 표시 숨김(`WHITE_LABEL`)은 게시 위반, 묶음 다운로드(`BULK_DOWNLOAD`)는 403 `ENT-004`.

확인: `GET /api/auth/events/{eventId}/usage` 의 `entitlement` 가 채워져 있으면 게시할 수 있다.

## 5. 계약으로 주고 싶을 때 (선택)

`POST /api/admin/contracts` 로 DRAFT 계약을 만든 뒤 `PATCH /api/admin/contracts/{contractId} { "status": "ACTIVE" }` 로 바꾸면
계약 조건대로 이용권이 생긴다. 요청 모양은 OpenAPI(`ContractRequest`)를 본다.

## 되돌리기

`POST /api/admin/entitlements/{entitlementId}/revoke { "reason": "..." }` — 회수하면 새 게시·촬영이 막힌다.
로컬 DB 를 통째로 비우려면 `docker compose down -v`(볼륨까지 삭제)를 쓴다.
