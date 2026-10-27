# ── Build stage ───────────────────────────────────────────────
# 산출물이 JAR(아키텍처 무관)이라 빌드는 항상 빌더 네이티브 아키텍처에서 수행한다.
# (멀티아치 빌드 시 amd64 를 QEMU 에뮬레이션으로 Gradle 돌리는 것을 피하기 위함)
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# 빌드 스크립트만 먼저 복사해 의존성 레이어를 캐시한다 — 소스가 바뀌어도 이 레이어는 재사용된다.
# settings.gradle 이 compose-core/compose-lambda 를 include 하므로 두 모듈의 빌드 파일도 필요하다.
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY compose-core/build.gradle compose-core/build.gradle
COPY compose-lambda/build.gradle compose-lambda/build.gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q

# compose-lambda 소스는 서버 JAR 에 들어가지 않으므로 복사하지 않는다 (bootJar 의존 그래프 밖)
COPY src src
COPY compose-core/src compose-core/src
RUN ./gradlew bootJar -x test --no-daemon -q

# ── Run stage ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 이 구현은 KST 일관이다: Clock 빈이 Asia/Seoul 고정이고 @Scheduled cron 에 zone 지정이 없어
# 시스템 TZ 를 따른다. UTC 로 두면 새벽 배치(01:00/02:00/02:30)가 KST 오전 10~11시에 돈다.
ENV TZ=Asia/Seoul

RUN addgroup -S app && adduser -S app -G app

# 참가자 자유 문구 서버 렌더(TextRenderer)가 Java2D 로 글자를 그린다 — 글꼴 래스터라이저가 시스템 freetype 을 쓴다.
# 글꼴 파일은 JAR 안의 Pretendard 라 시스템 글꼴은 필요 없다. ⚠️ 실제 이미지에서 렌더 확인 필요(실연동 미확인)
RUN apk add --no-cache freetype fontconfig

# H2 파일 DB 등 쓰기 경로. named volume 이 이 디렉터리에 마운트되면
# 소유권(app:app)을 그대로 물려받아 비루트 유저로도 쓰기가 가능하다.
RUN mkdir -p /data && chown app:app /data

USER app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
