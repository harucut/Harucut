# ADR-0005 · 스키마 변경을 Flyway 로 옮기는 순서

- **상태**: 부분 채택 — 도구와 V1 초안은 들어왔고, **운영 편입(baseline)은 대기**
- **날짜**: 2026-10-26
- **관련**: R5 기획서 17절, `src/main/resources/db/migration/`, `FlywayMysqlMigrationTest`

## 1. 맥락

운영은 `spring.jpa.hibernate.ddl-auto: update` 다(`application-prod.yaml`). 엔티티가 바뀐 코드가 main 에
머지되면 배포 순간 Hibernate 가 운영 스키마를 고친다. 되돌릴 수 없고, 어떤 DDL 이 나갔는지 기록도 없다.
고도화(P1~)는 테이블·컬럼을 많이 추가하고, 기존 유니크(`uk_compose_job_user_idempotency`)를 FK 지원
인덱스를 지키며 바꿔야 한다. update 로는 이런 순서 있는 전환을 할 수 없다.

## 2. 결정

1. **Flyway 를 넣되 기본은 꺼 둔다** — `spring.flyway.enabled: ${FLYWAY_ENABLED:false}`.
   이 상태로 main 에 들어가도 운영 동작은 지금과 같다(update 가 계속 맞춘다).
2. **V1 은 로컬 스키마 초안이다** — 로컬 MySQL(update 로 생성)의 `mysqldump --no-data`.
   파일 머리에 "운영 SHOW CREATE TABLE 대조 전"이라고 적었다. BATCH_* 는 Spring Batch 스크립트가 만들므로 뺐다.
3. **이후의 스키마 변경은 모두 V2+ migration 으로 쓴다.** 엔티티만 바꾸는 PR 을 받지 않는다.
   flyway 가 꺼진 동안에는 update 가 같은 변경을 만들지만, migration 이 원본이다.
4. **실제 MySQL 로 검증한다** — `./gradlew mysqlTest` 가 Testcontainers MySQL 8.0 에 모든 migration 을
   적용하고 `ddl-auto=validate` 로 엔티티와 대조한다. 기본 `test` 에서는 빠진다(Docker 필요).
   migration 을 추가하는 PR 은 이 결과를 본문에 적는다.
5. **자동 baseline 을 쓰지 않는다** — `baseline-on-migrate: false`. 잘못된 DB 를 조용히 기준선으로 삼는다.

## 3. 운영 편입 절차 (사용자 승인 후)

1. 운영 백업, 복원 리허설.
2. 운영에서 각 테이블의 `SHOW CREATE TABLE`·`SHOW INDEX` 를 받아 V1 과 대조. 다르면 V1 을 운영 기준으로 고친다.
3. 스테이징(운영 스키마 덤프 + 가짜 데이터)에서: `flyway baseline -baselineVersion=1` → V2+ `migrate` →
   앱을 `FLYWAY_ENABLED=true`, `ddl-auto=validate` 로 기동.
4. 운영에 같은 순서. **flyway 를 켜는 배포와 ddl-auto 를 validate 로 바꾸는 배포는 같은 배포다** —
   둘이 동시에 스키마를 고치면 안 된다.

## 4. 로컬에서 mysqlTest 돌리기

Docker 가 필요하다. Colima 를 쓰면 Testcontainers 가 소켓을 못 찾으므로 환경변수를 준다:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew mysqlTest
```

## 5. 남은 위험

- V1 이 운영과 다를 수 있다 — 3절 2번 전까지 V1 은 초안이다.
- flyway 가 꺼진 동안 V2+ 와 update 가 만든 운영 스키마가 미묘하게 다를 수 있다(인덱스 이름 등).
  편입 때 대조 대상에 V2+ 도 포함한다.
