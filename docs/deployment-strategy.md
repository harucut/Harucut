# 배포 전략 — 실행 문서

> **왜 이렇게 정했는지는 [ADR-0004](adr-0004-cicd-pipeline.md)에 있다.** 그 뒤 바뀐 것: 이미지 저장소가 Docker Hub 에서
> GHCR 로, 배포 저장소가 popeye0618/Harucut 에서 **harucut/Harucut** 으로, 서버의 AWS 자격이 접근 키에서 서버 역할로
> 옮겨 갔다(2026-11-22). 이 문서는 지금 **무엇을 어떻게 하는지**만 적는다. 그림은 [구조도 ④](harucut-architecture.drawio).

---

## 한 줄 요약

**harucut/Harucut 의 main 에 머지하면 끝난다.** GitHub Actions 가 이미지를 만들어 GHCR(`ghcr.io/harucut/harucut`)에 올리고,
EC2 에 접속해 새 이미지로 갈아 끼운다. **EC2 는 받아서 띄우기(pull + up)만 한다.**
테스트는 PR 단계(`ci.yml`, main·develop 으로 들어오는 PR)에서 끝났다 — 배포에서는 다시 돌리지 않는다.

---

## 1. 전체 흐름 (`.github/workflows/deploy.yml`)

```
[사람]   PR 머지 → main                              ← 사람이 하는 일은 여기까지
   │
[GitHub] push:main → deploy 워크플로 (한 번에 하나씩, 진행 중인 배포는 취소하지 않는다)
   │
   ├─ job: build
   │   checkout → buildx → GHCR 로그인(GITHUB_TOKEN)
   │   Dockerfile 멀티스테이지 빌드(Gradle 은 이미지 안에서 돈다, 레이어 캐시 gha)
   │   → ghcr.io/harucut/harucut:<커밋 SHA 40자>  +  :latest
   │
   ├─ job: deploy (build 가 성공해야 시작)
   │   deploy/prod/compose.yml → 서버 /home/ubuntu/harucut/compose.yml 로 복사(덮어쓴다)
   │   SSH → .env 의 IMAGE_TAG 를 새 SHA 로 → compose pull app → compose up -d
   │   관리 포트 8081 의 health 를 3분까지 기다린다
   │     뜨면: 만든 지 일주일이 넘은 안 쓰는 이미지를 지운다
   │     안 뜨면: 앱 로그 80줄을 남기고 직전 IMAGE_TAG 로 되돌린다 — 스키마는 되돌리지 않는다
```

---

## 2. 파일

| 파일 | 어디에 | 역할 |
|---|---|---|
| `.github/workflows/deploy.yml` | 저장소 | main push → 빌드·배포 |
| `.github/workflows/ci.yml` | 저장소 | PR 시험(전체 시험 + 실제 MySQL) |
| `deploy/prod/compose.yml` | 저장소 → 배포 때 서버로 복사 | 운영 구성(app·mysql·redis). **서버에서 손으로 고치면 다음 배포에 덮인다** |
| `.env` | **서버에만** (`/home/ubuntu/harucut/.env`) | 비밀값과 지금 떠 있는 `IMAGE_TAG`. 견본은 `deploy/prod/.env.example` — 저장소·이미지에 넣지 않는다 |
| `Dockerfile` | 저장소 | 빌드 `eclipse-temurin:21-jdk-alpine`, 실행 `eclipse-temurin:21-jre-alpine`. 태그를 고정하지 않아 **빌드할 때마다 그 태그의 최신 JDK 패치를 받는다** — 패치를 운영에 내려면 다시 배포한다 |

> 저장소 루트의 `docker-compose.yml` 은 **로컬·부하 시험용**이다. 운영에 쓰지 않는다.

---

## 3. 최초 세팅 (새 서버·새 저장소일 때 한 번)

### GitHub — harucut/Harucut → Settings → Secrets and variables → Actions

| 이름 | 값 |
|---|---|
| `EC2_HOST` | 서버 공인 IP 또는 도메인 |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | 개인키 **전문** (`-----BEGIN ...` 줄부터 끝까지) |

- GHCR 은 워크플로의 `GITHUB_TOKEN` 으로 push·pull 한다 — 따로 넣을 비밀이 없다.
- 첫 push 가 harucut 조직에 패키지(`harucut`)를 만든다. 조직 설정이 Actions 의 패키지 생성을 막으면 build 가 `denied` 로 실패한다(§6).
- deploy.yml 은 이 저장소 main 에 처음 push 될 때 Actions 에 등록된다.

### EC2

- [ ] 아키텍처 `x86_64` — 워크플로가 `linux/amd64` 만 빌드한다(`uname -m` 이 `aarch64` 면 `exec format error`)
- [ ] `/home/ubuntu/harucut/.env` — 견본은 `deploy/prod/.env.example`. compose.yml 은 첫 배포가 넣는다
- [ ] 배포 유저가 docker 를 쓸 수 있다 — `usermod -aG docker ubuntu`
- [ ] **AWS 자격은 서버 역할(instance profile)** 로 준다. `.env` 에 접근 키를 두지 않는다 — SDK 기본 자격 체인이 IMDSv2 로 받는다.
      컨테이너에서 IMDS 에 닿게 인스턴스 메타데이터의 **hop limit 을 2** 로 둔다(도커 브리지가 한 홉). 역할에 필요한 권한:

  | 서비스 | 대상 | 권한 | 쓰는 곳 |
  |---|---|---|---|
  | S3 | 사진 버킷 하나(`S3_BUCKET`) | `GetObject`·`PutObject`·`DeleteObject`·`AbortMultipartUpload` | 업로드·다운로드 presign, 합성 입력·결과, 묶음 내보내기(멀티파트), 삭제 |
  | S3 | 같은 버킷 | `ListBucket` | 없는 key 가 403 이 아니라 404 로 오게(`headObject`) |
  | SQS | 합성 결과 큐(`COMPOSE_RESULT_QUEUE_URL`) | `ReceiveMessage`·`DeleteMessage` | `ComposeResultConsumer` |
  | Lambda | 합성 함수(`COMPOSE_LAMBDA_FUNCTION`) | `InvokeFunction` | `LambdaComposeExecutor` |

  합성 함수는 버킷 이름을 서버가 보낸 payload 에서 받는다 — 함수의 실행 역할이 같은 버킷을 읽고 쓸 수 있어야 하고, 결과는 Destination 으로 위 큐에 간다.
- [ ] 보안그룹 22 — Actions 러너가 SSH 로 들어온다(러너 IP 는 고정이 아니다. 22 를 닫으려면 §7)

> ⚠️ **역할 자격은 몇 시간마다 바뀐다.** 그 자격으로 서명한 presigned URL 은 자격이 끝나면 함께 끝난다 —
> 24시간으로 서명한 URL(보관함 목록의 `viewUrl`·`downloadUrl`, 옛 업로드 URL)도 몇 시간 안에 죽을 수 있다.
> 화면은 URL 을 오래 들고 있지 말고 목록을 다시 받는다. 짧은 URL(5~10분)은 영향이 없다.

### 저장소를 옮긴 뒤 첫 배포 전에 한 번 (2026-11-22, 옛 서버를 복사한 새 서버)

자동 되돌림은 `.env` 의 직전 `IMAGE_TAG` 를 **새 이름**(`ghcr.io/harucut/harucut`)으로 찾는다. 서버에 있는 직전 이미지는
옛 이름(`ghcr.io/popeye0618/harucut`)이라 그대로는 못 찾고, 첫 배포가 실패하면 앱이 내려간 채로 남는다. 첫 배포 전에 새 이름을 붙여 둔다:

```bash
cd /home/ubuntu/harucut
TAG=$(grep '^IMAGE_TAG=' .env | cut -d= -f2-)
docker tag "ghcr.io/popeye0618/harucut:${TAG}" "ghcr.io/harucut/harucut:${TAG}"
```

`.env` 에 `APP_IMAGE` 를 따로 두었다면 compose.yml 의 기본값 대신 그 값을 쓴다 — 지우거나 새 이름으로 바꾼다.

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
feat/xxx → PR → develop → (운영에 낼 때) develop → main 머지 = 운영 배포
```

Actions 탭에서 진행을 본다. 지금 떠 있는 버전은 서버 `.env` 의 `IMAGE_TAG`(= 커밋 SHA)다.

---

## 5. 롤백

- **자동**: 새 이미지가 3분 안에 health 를 통과하지 못하면 직전 `IMAGE_TAG` 로 되돌린다.
- **수동**: 서버에서 `.env` 의 `IMAGE_TAG` 를 되돌릴 커밋 SHA 로 바꾸고 `docker compose up -d`.
  배포가 성공하면 만든 지 일주일이 넘은 안 쓰는 이미지를 지운다 — 서버에 없는 SHA 는 GHCR 에서 받는다(`docker login ghcr.io` 필요).
- **스키마는 되돌리지 않는다.** Flyway migration 을 지난 배포를 되돌릴 때는 DB 백업 복원과 함께다([ADR-0005](adr-0005-flyway-adoption.md)).

---

## 6. 안 될 때 보는 곳

| 증상 | 원인 | 확인·조치 |
|---|---|---|
| build 에서 `denied` / `permission_denied` (push) | 조직이 Actions 의 패키지 생성·쓰기를 막았다 | 조직 Settings → Packages, 패키지 Settings → Manage Actions access 에 harucut/Harucut 을 Write 로 |
| deploy 에서 `unauthorized` (pull) | 패키지가 이 저장소에 묶이지 않았다 | 패키지 Settings → Manage Actions access |
| 컨테이너가 뜨자마자 죽음 | `.env` 누락·DB 접속·Flyway | `docker compose logs app` — 기동 예외에 원인이 나온다 |
| `Unable to load credentials` | 서버 역할이 없거나 hop limit 이 1 | 인스턴스에 역할 연결, 메타데이터 hop limit 2 |
| `AccessDenied` (S3·SQS·Lambda) | 역할 권한 부족 | §3 권한 표 |
| `exec format error` | 이미지 아키텍처 불일치 | `uname -m` → 워크플로 `platforms` |
| SSH 단계에서 멈춤 | 보안그룹 22 차단 | 인바운드 규칙 |

### 서버에서 직접 볼 것

```bash
cd /home/ubuntu/harucut
docker compose ps                  # 뭐가 떠 있나
docker compose logs -f app         # 앱 로그
grep '^IMAGE_TAG=' .env            # 지금 도는 이미지 = 커밋
```

---

## 7. 앞으로 (지금은 안 한다)

| 언제 | 무엇으로 |
|---|---|
| 배포가 하루 10회를 넘으면 | 무중단 배포(블루/그린). 지금은 재기동에 수십 초 끊긴다 |
| 인바운드 22를 닫고 싶으면 | **AWS SSM Send-Command** — EC2가 SSM에 *나가서* 붙으므로 인바운드가 필요 없다 |
| 인스턴스가 2대가 되면 | SSH 배포가 대수만큼 늘어난다. SSM 또는 ECS. 앱 안의 일일 배치도 한 대 전제다(decisions.md 2026-11-03) |
