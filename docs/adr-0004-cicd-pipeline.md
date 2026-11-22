# ADR-0004 — 배포 파이프라인: main 머지 하나로 테스트·빌드·배포가 끝난다

- **상태**: 채택됨 (2026-08-22) · **갱신 2026-11-22** — 이미지는 GHCR(`ghcr.io/harucut/harucut`), 배포 저장소는 harucut/Harucut,
  서버 AWS 자격은 서버 역할(instance profile)이다. 본문의 Docker Hub·`popeye0618` 은 채택 당시 기록이고, 지금 절차는 [deployment-strategy.md](deployment-strategy.md)
- **관련**: [ADR-0001](adr-0001-compose-result-channel.md) (끌어가는 쪽이 이긴다),
  [아키텍처 구조도 ④](harucut-architecture.drawio)

---

## 0. 한 문단 요약

**main에 머지하면 GitHub Actions가 테스트하고, 이미지를 빌드해 Docker Hub에 올리고, EC2에 배포까지 한다.**
빌드는 EC2가 아니라 **GitHub 러너**에서 돌고, EC2는 **받아서 띄우기(pull + up)만** 한다.
이 저장소는 **public이라 Actions 사용 분이 무료 무제한**이고 Docker Hub public 이미지도 무료라
**파이프라인 추가 비용은 0원**이다. 사람이 하는 일은 **PR을 머지하는 것 하나**로 줄어든다.

---

## 1. 요금 — 이게 0원인 이유

| 항목 | 요금 |
|---|---|
| **GitHub Actions (public 저장소, 표준 러너)** | **무료 · 사용 분 제한 없음** |
| 러너 사양 (`ubuntu-latest`) | 2 vCPU / 7GB RAM / 14GB SSD |
| Docker Hub public 저장소 | 무료 |
| 러너 → Docker Hub 전송 | **우리 부담 아님** (러너가 GitHub 인프라) |
| EC2가 이미지를 받는 것 | 인바운드 전송이라 AWS 요금 **없음** |
| **합계** | **0원** |

> **주의 — 저장소를 private으로 바꾸면 요금 체계가 바뀐다.**
> private은 Free 플랜 기준 **월 2,000분 무료**이고 그 뒤로 과금된다.
> 지금 빌드가 3~5분이니 2,000분이면 월 400회 넘게 배포할 수 있어 여전히 여유롭지만,
> **"무제한"이 아니게 된다**는 점은 기억해 둔다.

**EC2 요금도 오히려 준다.** 빌드가 서버에서 사라지므로 배포 때 CPU 크레딧(t 계열)을 태우지 않는다.

---

## 2. 결정 1 — 빌드는 GitHub 러너에서, EC2는 pull + up만

### EC2에서 빌드하면 안 되는 이유

**(1) 배포가 곧 장애 시간이 된다**

`Dockerfile` 빌드 스테이지는 JDK 이미지를 받고 `./gradlew dependencies` → `bootJar`를 돈다.
EC2에서 돌면 Gradle 데몬과 javac가 **CPU를 전부 가져가고**,
그 몇 분 동안 **같은 인스턴스에서 서비스 중인 앱의 응답이 느려진다.**

**(2) 프리티어 1GB에서는 OOM 위험**

t3.micro는 메모리 1GB다. MySQL·Redis·앱이 떠 있는 위에 Gradle 빌드를 얹으면
스왑으로 넘어가거나 OOM Killer가 뭔가를 죽인다.

**(3) 사양이 아깝다**

러너는 **2 vCPU / 7GB**를 공짜로 준다. t3.micro보다 좋은 사양이다.

### 로컬 빌드가 아니라 러너 빌드인 이유

로컬에서 빌드해 올리는 방식도 EC2 부하로는 똑같이 좋다 — **그 축으로는 못 고른다.**
갈리는 지점은 세 가지다.

| 축 | 로컬 빌드 | **러너 빌드 (채택)** |
|---|---|---|
| 사람이 하는 일 | 커밋 → 테스트 확인 → 빌드 → push → 배포 버튼 | **머지 하나** |
| 테스트–이미지 일치 | 규율로 지켜야 함 | **자동 보장** — 같은 잡에서 순서대로 돈다 |
| 빌드 환경 | 데스크탑 2대 (JDK·OS가 다를 수 있음) | 고정된 러너 이미지 |
| 이미지 업로드 | 가정용 업로드 회선 | 데이터센터 간 |

**결정적인 것은 두 번째다.** 로컬 빌드는 "테스트가 통과한 커밋"과 "올린 이미지"가
**같다는 보장이 사람의 기억에 달려 있다.** 러너에서는 테스트 → 빌드 → push가 한 잡 안에서
순서대로 돌기 때문에, **테스트가 깨지면 이미지가 아예 만들어지지 않는다.**

이 프로젝트는 이미 "조용히 성공하는 실패"로 한 번 당했다 —
JDK 17 + 한국어 Windows에서 인코딩이 MS949라 **한글이 깨진 채 빌드가 성공**했다.
사람의 규율에 기대는 단계는 줄일수록 좋다.

---

## 3. 결정 2 — 레지스트리는 Docker Hub, 이미지는 public

| 축 | Docker Hub (채택) | GHCR |
|---|---|---|
| 요금 | public 무료 | public 무료 |
| 러너에서 push할 때 시크릿 | **2개** (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`) | 0개 (`GITHUB_TOKEN` 자동) |
| EC2 pull | public이면 인증 불필요 | public이면 인증 불필요 |
| pull 제한 | **IP·계정 단위 rate limit 있음** | 없음 |

**시크릿 2개와 pull 제한을 감수하고 Docker Hub를 고른다** — 이미 쓰던 곳이고,
이미지가 GitHub 밖에서도 바로 보인다.

두 가지만 대비해 둔다.

1. **이미지는 반드시 public.** private이면 EC2에 자격증명을 심어야 하고, 무료 플랜은 private 1개뿐이다.
2. **EC2에서 최초 1회 `docker login`.** EC2는 고정 IP 하나라 익명 pull 제한에 걸릴 수 있다.
   로그인해 두면 계정 단위 한도로 올라간다.

```bash
# EC2에서 한 번만
docker login -u popeye0618
```

> pull 제한에 실제로 물리면 GHCR로 옮긴다. 그때 바뀌는 건 워크플로의 이미지 이름과
> 로그인 스텝뿐이고 **EC2 쪽은 그대로다.**

---

## 4. 결정 3 — 태그는 커밋 SHA, `latest`는 편의용

```
popeye0618/harucut:sha-4970c67      ← 배포는 항상 이걸로
popeye0618/harucut:latest           ← 사람이 손으로 받아볼 때만
```

**`latest`만 쓰면 안 되는 이유가 둘이다.**

1. **지금 서버에서 도는 게 어느 커밋인지 알 수 없다.** 장애 때 제일 먼저 알아야 하는 것이다.
2. **롤백 대상이 없다.** `latest`는 항상 최신을 가리키므로 "직전 것"이라는 개념이 없다.

SHA 태그가 있으면 롤백이 **배포 워크플로에 이전 태그를 넣고 실행**하는 것으로 끝난다 (→ §6).

---

## 5. 배포 시퀀스

```
[사람]  PR 머지 → main
   │
   ▼
[GitHub]  push:main 감지 → deploy 워크플로 시작
   │
   ├─ job: build  (ubuntu-latest 러너)
   │    1. checkout
   │    2. JDK 21 + Gradle 캐시 복원
   │    3. ./gradlew test          ◀── 실패하면 여기서 끝. 이미지도 안 만든다
   │    4. TAG = sha-<커밋 앞 7자>
   │    5. Docker Hub 로그인       (시크릿 2개)
   │    6. docker buildx build --platform linux/amd64 --push
   │         → popeye0618/harucut:sha-xxxxxxx
   │         → popeye0618/harucut:latest
   │       (Dockerfile 멀티스테이지: 컨테이너 안에서 bootJar 까지)
   │    7. outputs.tag 로 태그를 다음 잡에 넘김
   │
   ▼
   ├─ job: deploy  (needs: build — build 실패면 시작조차 안 함)
   │    8. SSH → EC2:22
   │    9. cd /srv/harucut
   │   10. IMAGE_TAG=sha-xxxxxxx docker compose -f docker-compose.prod.yml pull app
   │   11. docker compose -f docker-compose.prod.yml up -d app
   │   12. docker image prune -f
   │   13. docker compose ps        ◀── 지금 도는 이미지를 로그에 남긴다
   │
   ▼
[EC2]  이전 컨테이너 graceful 종료(stop_grace_period 60s)
       → 새 컨테이너 기동
```

**EC2가 하는 일은 10~11번 두 줄뿐이다.** 빌드는 러너에서 이미 끝났다.

### 걸리는 시간 (대략)

| 단계 | 첫 배포 | 캐시 후 |
|---|---|---|
| test | 1~2분 | 30초~1분 |
| docker build + push | 3~4분 | 1~2분 |
| 배포 (pull + up) | 30초~1분 | 30초~1분 |
| **합계** | **5~7분** | **2~4분** |

---

## 6. 워크플로 — 트리거 둘, 잡 둘

`push:main`은 자동 배포, `workflow_dispatch`는 **롤백 버튼**이다. 한 파일로 둘 다 처리한다.

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

# 배포가 겹치면 순서가 뒤집힐 수 있다 — 한 번에 하나만 돌린다
concurrency:
  group: deploy
  cancel-in-progress: false

jobs:
  build:
    # 롤백(수동 실행)일 때는 빌드하지 않는다. 이미 있는 이미지를 쓴다
    if: github.event_name == 'push'
    runs-on: ubuntu-latest
    outputs:
      tag: ${{ steps.meta.outputs.tag }}
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'

      # 테스트가 먼저다. 실패하면 이 아래로 못 간다 = 이미지가 안 만들어진다
      - run: ./gradlew test

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
          platforms: linux/amd64      # EC2가 Graviton이면 linux/arm64
          push: true
          tags: |
            popeye0618/harucut:${{ steps.meta.outputs.tag }}
            popeye0618/harucut:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    needs: [build]
    # 자동 배포면 build 성공을 요구하고, 롤백이면 build를 건너뛰었어도 진행한다
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

**시크릿 5개**: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`.

> `cache-from/to: type=gha`는 도커 레이어를 러너 캐시에 남긴다 — 두 번째 배포부터 빌드가 크게 줄어든다.
> Actions 캐시는 **저장소당 10GB**이고 넘으면 오래된 것부터 밀려난다. 문제되면 `mode=max` → `mode=min`.

---

## 7. 운영용 compose 파일을 분리해야 한다

지금 `docker-compose.yml`의 `app` 서비스는 `build:`를 쓰고 `profiles: ["load"]`가 붙어 있다 —
**부하 테스트용이지 운영용이 아니다.**

```yaml
# docker-compose.prod.yml
services:
  app:
    image: popeye0618/harucut:${IMAGE_TAG:-latest}
    # build: 없음 — 서버는 빌드하지 않는다. 이 한 줄이 이 ADR의 요점이다
    stop_grace_period: 60s
    restart: unless-stopped
    env_file: [.env]
    ports: ["8080:8080"]
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_healthy }
```

> `.env`는 이미지에 넣지 않는다. **서버에만 둔다.** public 이미지라 더욱 그렇다.

---

## 8. 이 결정으로 얻는 것

| 항목 | 전 (EC2 빌드) | 후 |
|---|---|---|
| 사람이 하는 일 | SSH 접속 → git pull → up --build | **PR 머지 하나** |
| 배포 중 서버 부하 | 빌드가 CPU·메모리를 다 씀 | **pull + 재기동만** |
| 프리티어 1GB OOM | 가능 | **빌드가 서버에서 안 돌아 해당 없음** |
| 테스트–배포 일치 | 사람이 확인 | **테스트 실패 시 이미지가 안 만들어짐** |
| 롤백 | git checkout + 재빌드 (수 분) | **이전 태그 입력 → 수십 초** |
| 지금 도는 코드 | 서버 들어가서 git log | **이미지 태그가 곧 커밋** |
| 배포 가능한 곳 | 서버에 SSH 되는 곳 | **브라우저가 있는 어디서든** |
| 추가 요금 | — | **0원** |

---

## 9. 실행 항목

1. `docker-compose.prod.yml` 추가 — `image:` 사용, **`build:` 없음** (→ §7)
2. `.github/workflows/deploy.yml` 추가 — §6 그대로
3. 기존 워크플로에서 **EC2 빌드(`--build`) 제거**
4. 시크릿 5개 등록 (→ §6)
5. Docker Hub 저장소를 **public**으로 생성
6. EC2에서 `docker login` 1회 (→ §3)
7. **EC2 아키텍처 확인** — x86이면 `linux/amd64`, Graviton(t4g)이면 `linux/arm64`.
   안 맞으면 컨테이너가 `exec format error`로 죽는다
8. EC2에 `/srv/harucut` 준비 — `docker-compose.prod.yml`과 `.env`를 둔다

---

## 10. 재검토 트리거

- **빌드가 5분을 넘으면** — 캐시 설정을 다시 보거나 테스트를 분리한다
- **Docker Hub pull 제한에 걸리면** — EC2 `docker login`부터, 그래도 걸리면 GHCR로 옮긴다
  (바뀌는 건 워크플로 두 스텝뿐, EC2는 그대로)
- **저장소를 private으로 바꾸면** — Actions가 월 2,000분 무료로 바뀐다. 지금 빌드 시간이면 400회분이다
- **배포가 하루 10회를 넘으면** — 무중단 배포(블루/그린)를 검토한다. 지금은 재기동에 수십 초 끊긴다
- **인스턴스를 2대로 늘리면** — SSH 배포가 대수만큼 늘어난다. SSM 또는 ECS로 옮긴다
- **인바운드 22를 닫고 싶어지면** — AWS SSM Send-Command로 교체한다.
  EC2가 SSM에 **나가서 붙는** 구조라 인바운드가 필요 없다 —
  SQS를 고른 논리(→ [ADR-0001](adr-0001-compose-result-channel.md))와 같은 모양이다
