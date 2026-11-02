# 배포 전략 — 실행 문서

> **왜 이렇게 정했는지는 [ADR-0004](adr-0004-cicd-pipeline.md)에 있다.**
> 이 문서는 **무엇을 어떻게 하는지**만 적는다. 그림은 [구조도 ④](harucut-architecture.drawio).

---

## 한 줄 요약

**main에 머지하면 끝난다.** GitHub Actions가 테스트하고, 이미지를 만들어 Docker Hub에 올리고,
EC2에 접속해 새 이미지로 갈아 끼운다. **EC2는 받아서 띄우기(pull + up)만 한다.**
추가 요금은 **0원**이다 (public 저장소 → Actions 무제한 무료).

---

## 1. 전체 흐름

```
[사람]   PR 머지 → main                              ← 사람이 하는 일은 여기까지
   │
[GitHub] push:main 감지 → deploy 워크플로 시작
   │
   ├─ job: build ─────────────────────── ubuntu-latest (2 vCPU / 7GB)
   │   1  checkout
   │   2  JDK 21 + Gradle 캐시 복원
   │   3  ./gradlew test        ◀ 실패하면 여기서 끝. 이미지도 안 만든다
   │   4  TAG = sha-<커밋 앞 7자>
   │   5  Docker Hub 로그인
   │   6  buildx build --push   ◀ Dockerfile 멀티스테이지 (컨테이너 안 JDK가 bootJar까지)
   │   7  → popeye0618/harucut:sha-xxxxxxx  +  :latest
   │
   ├─ job: deploy ────────────────────── needs: build (실패하면 시작조차 안 함)
   │   8  SSH → EC2:22
   │   9  cd /srv/harucut
   │  10  compose pull app      ◀ EC2가 하는 일은
   │  11  compose up -d app     ◀ 이 두 줄이 전부
   │  12  image prune -f
   │  13  compose ps            ◀ 지금 도는 이미지를 로그에 남긴다
   │
[EC2]  이전 컨테이너 graceful 종료(60s) → 새 컨테이너 기동
```

### 걸리는 시간

| 단계 | 첫 배포 | 캐시 후 |
|---|---|---|
| test | 1~2분 | 30초~1분 |
| build + push | 3~4분 | 1~2분 |
| deploy (pull + up) | 30초~1분 | 30초~1분 |
| **합계** | **5~7분** | **2~4분** |

---

## 2. 파일 세 개

| 파일 | 어디에 | 역할 |
|---|---|---|
| `.github/workflows/deploy.yml` | 저장소 | 테스트 · 빌드 · 배포 |
| `docker-compose.prod.yml` | 저장소 + **EC2 `/srv/harucut/`** | 운영용 — `image:`를 쓰고 `build:`가 없다 |
| `.env` | **EC2에만** | 비밀값. 이미지에도, 저장소에도 넣지 않는다 |

> `docker-compose.yml`(기존)은 **부하 테스트용**이다. `app` 서비스에 `build:`와
> `profiles: ["load"]`가 붙어 있다. 운영에 쓰지 않는다.

### 2-1. `docker-compose.prod.yml`

```yaml
services:
  app:
    image: popeye0618/harucut:${IMAGE_TAG:-latest}
    # build: 없음 — 서버는 빌드하지 않는다. 이 한 줄이 이 전략의 요점이다
    container_name: harucut-app
    restart: unless-stopped
    stop_grace_period: 60s
    env_file: [.env]
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: "jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
      REDIS_HOST: redis
    ports: ["8080:8080"]      # 관리 포트 8081은 열지 않는다
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_healthy }

  mysql:
    image: mysql:8.0
    container_name: harucut-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${DB_USERNAME}
      MYSQL_PASSWORD: ${DB_PASSWORD}
      TZ: Asia/Seoul
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    volumes: [harucut-mysql-data:/var/lib/mysql]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    container_name: harucut-redis
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  harucut-mysql-data:
```

> **DB 포트를 호스트로 열지 않았다.** 기존 파일은 `3306:3306`을 열지만 그건 로컬 개발 편의다.
> 운영에서 열면 인터넷에서 MySQL이 보인다.

### 2-2. `.github/workflows/deploy.yml`

```yaml
name: deploy

on:
  push:
    branches: [main]
  workflow_dispatch:
    inputs:
      tag:
        description: '배포할 이미지 태그 (롤백용, 예: sha-e29fae2)'
        required: true

# 배포가 겹치면 순서가 뒤집힐 수 있다 — 한 번에 하나만
concurrency:
  group: deploy
  cancel-in-progress: false

jobs:
  build:
    if: github.event_name == 'push'      # 롤백일 땐 빌드하지 않는다
    runs-on: ubuntu-latest
    outputs:
      tag: ${{ steps.meta.outputs.tag }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin', cache: 'gradle' }

      - run: ./gradlew test          # 실패하면 이 아래로 못 간다

      - id: meta
        run: echo "tag=sha-$(git rev-parse --short HEAD)" >> $GITHUB_OUTPUT

      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/amd64       # Graviton(t4g)이면 linux/arm64
          push: true
          tags: |
            popeye0618/harucut:${{ steps.meta.outputs.tag }}
            popeye0618/harucut:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    needs: [build]
    if: always() && (needs.build.result == 'success' || github.event_name == 'workflow_dispatch')
    runs-on: ubuntu-latest
    steps:
      - id: pick
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            echo "tag=${{ inputs.tag }}" >> $GITHUB_OUTPUT
          else
            echo "tag=${{ needs.build.outputs.tag }}" >> $GITHUB_OUTPUT
          fi

      - uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            set -e
            cd /srv/harucut
            export IMAGE_TAG=${{ steps.pick.outputs.tag }}
            docker compose -f docker-compose.prod.yml pull app
            docker compose -f docker-compose.prod.yml up -d app
            docker image prune -f
            docker compose -f docker-compose.prod.yml ps
```

---

## 3. 최초 세팅 체크리스트

한 번만 하면 되는 것들이다.

### EC2

- [ ] **아키텍처 확인** — `uname -m`
  - `x86_64` → 워크플로의 `platforms: linux/amd64` (기본값)
  - `aarch64` → **`linux/arm64`로 바꿔야 한다.** 안 그러면 컨테이너가 `exec format error`로 죽는다
- [ ] `/srv/harucut` 디렉터리 생성
- [ ] `docker-compose.prod.yml` 배치
- [ ] `.env` 배치 — **`COMPOSE_LAMBDA_FUNCTION`과 `COMPOSE_RESULT_QUEUE_URL`이 빠지면 앱이 기동에서 죽는다**
- [ ] `docker login -u popeye0618` **1회** — EC2는 고정 IP 하나라 익명 pull 제한에 걸릴 수 있다
- [ ] 배포 유저가 docker를 쓸 수 있는지 — `usermod -aG docker $USER`

### Docker Hub

- [ ] `popeye0618/harucut` 저장소를 **public**으로 생성
- [ ] Access Token 발급 (Account Settings → Security) — 비밀번호 말고 토큰을 쓴다

### GitHub Secrets

| 이름 | 값 |
|---|---|
| `DOCKERHUB_USERNAME` | Docker Hub 계정명 |
| `DOCKERHUB_TOKEN` | 위에서 발급한 Access Token |
| `EC2_HOST` | EC2 공인 IP 또는 도메인 |
| `EC2_USER` | `ubuntu` / `ec2-user` 등 |
| `EC2_SSH_KEY` | 개인키 **전문** (`-----BEGIN ...` 줄부터 끝까지) |

### 보안그룹

- [ ] 22번이 열려 있어야 한다 — Actions 러너에서 SSH로 들어온다
  (러너 IP는 고정이 아니라 대역을 좁히기 어렵다. 22를 닫고 싶으면 → §7)

---

## S3 버킷 CORS (배포 전 확인 — 실환경 미확인)

브라우저가 S3 presigned URL 을 직접 부른다. 빠지면 화면에서 업로드·저장이 조용히 실패한다.

| 메서드 | 쓰는 곳 | 헤더 | 없으면 |
|---|---|---|---|
| PUT | 자산·촬영 입력·합성 원본 업로드 | `Content-Type`, `If-None-Match` | 업로드 전부 실패. `If-None-Match` 만 없으면 `STORAGE_UPLOAD_CONDITIONAL_PUT=false` 로 배포할 수 있다 |
| GET | 결과를 `fetch` 로 받아 `navigator.share({files})` 로 넘긴다(iOS 사진첩 저장) | 없음(`Authorization` 을 보내지 않는다) | FE 가 attachment 링크로 내려받기로 넘어간다 |

```json
[{
  "AllowedOrigins": ["https://harucut.com", "https://www.harucut.com"],
  "AllowedMethods": ["PUT", "GET", "HEAD"],
  "AllowedHeaders": ["Content-Type", "If-None-Match"],
  "ExposeHeaders": ["ETag"],
  "MaxAgeSeconds": 3000
}]
```

확인: `aws s3api get-bucket-cors --bucket <버킷>` (읽기 전용, 사용자 허락 후).

## 4. 평소 배포

```
feat/xxx 작업 → PR → main 머지
```

**끝이다.** Actions 탭에서 진행 상황을 본다.

확인하고 싶으면 마지막 스텝 로그의 `docker compose ps` 출력을 본다 —
`IMAGE` 열에 방금 배포한 태그가 찍혀 있다.

---

## 5. 롤백

1. Actions → **deploy** 워크플로 → **Run workflow**
2. `tag` 입력란에 되돌릴 태그: `sha-e29fae2`
3. 실행

빌드를 건너뛰고 배포만 돈다. **수십 초.**

### 되돌릴 태그를 찾는 법

- Docker Hub의 Tags 탭에서 시간순으로 보인다
- 또는 `git log --oneline`의 커밋 해시 앞 7자가 곧 태그다 (`sha-` 접두사만 붙이면 된다)

---

## 6. 안 될 때 보는 곳

| 증상 | 원인 | 확인·조치 |
|---|---|---|
| `exec format error` | 이미지 아키텍처 불일치 | EC2에서 `uname -m` → 워크플로 `platforms` 수정 |
| `toomanyrequests: Rate limit` | Docker Hub 익명 pull 제한 | EC2에서 `docker login` (§3) |
| 컨테이너가 뜨자마자 죽음 | `.env` 값 누락 | `docker compose logs app` — 기동 예외 메시지에 어떤 키인지 나온다 |
| `unauthorized` (pull 단계) | 이미지가 private | Docker Hub 저장소를 public으로 |
| 배포는 성공인데 옛날 코드 | `latest` 태그를 썼고 캐시가 남음 | 항상 `sha-` 태그로 배포한다 |
| 테스트가 CI에서만 실패 | 로컬과 타임존/인코딩 차이 | 러너는 UTC다. `@Scheduled` 관련 테스트를 의심 |
| SSH 스텝에서 멈춤 | 보안그룹 22 차단 | 인바운드 규칙 확인 |

### 서버에서 직접 볼 것

```bash
cd /srv/harucut
docker compose -f docker-compose.prod.yml ps          # 뭐가 떠 있나
docker compose -f docker-compose.prod.yml logs -f app # 앱 로그
docker ps --format '{{.Names}}\t{{.Image}}'           # 지금 도는 이미지 = 커밋
```

---

## 7. 앞으로 (지금은 안 한다)

| 언제 | 무엇으로 |
|---|---|
| 배포가 하루 10회를 넘으면 | 무중단 배포(블루/그린). 지금은 재기동에 수십 초 끊긴다 |
| 인바운드 22를 닫고 싶으면 | **AWS SSM Send-Command** — EC2가 SSM에 *나가서* 붙으므로 인바운드가 필요 없다. SQS를 고른 논리(→ [ADR-0001](adr-0001-compose-result-channel.md))와 같은 모양이다 |
| 인스턴스가 2대가 되면 | SSH 배포가 대수만큼 늘어난다. SSM 또는 ECS |
| Docker Hub 제한에 계속 걸리면 | GHCR로 이전. 바뀌는 건 워크플로 두 스텝뿐, **EC2 쪽은 그대로다** |
| 저장소를 private으로 바꾸면 | Actions가 월 2,000분 무료로 바뀐다. 지금 빌드 시간이면 월 400회분 |

---

## 8. 이 전략으로 달라지는 것

| 항목 | 전 (EC2에서 `up --build`) | 후 |
|---|---|---|
| 사람이 하는 일 | SSH → git pull → up --build | **PR 머지 하나** |
| 배포 중 서버 부하 | 빌드가 CPU·메모리를 다 씀 | **pull + 재기동만** |
| 프리티어 1GB OOM | 가능 | **빌드가 서버에서 안 돌아 해당 없음** |
| 테스트–배포 일치 | 사람이 확인 | **테스트 실패 시 이미지가 안 만들어짐** |
| 롤백 | git checkout + 재빌드 (수 분) | **태그 입력 → 수십 초** |
| 지금 도는 코드 | 서버 들어가서 git log | **이미지 태그가 곧 커밋** |
| 배포 가능한 곳 | SSH 되는 곳 | **브라우저가 있는 어디서든** |
| 추가 요금 | — | **0원** |
