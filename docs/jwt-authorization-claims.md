# 인가 정보를 JWT로 옮기기 — 매 요청 DB 조회를 없앤 과정

> 이 문서는 **결정에 이르는 과정**을 남긴다. 결정 자체의 요약은 [`decisions.md`](decisions.md), 최종 필터 동작은 [`auth-flow.md`](auth-flow.md).
> 스프링 시큐리티 내부 동작에 대한 서술은 코드/문서를 확인한 것이고, 확인하지 않은 부분은 그렇게 표시했다.

---

## 1. 문제

`JwtAuthenticationFilter`가 **매 요청마다 DB를 읽고 있었다.**

```
JwtAuthenticationFilter
 ├ parse(token) → JwtClaims { publicId, type }
 │      "누군지는 알겠는데 권한을 모르겠다"
 ├ loadUserByPublicId(publicId)      ←←← DB 조회. 매 요청.
 ├ principal.getAuthorities()        ← status/role로 계산
 └ SecurityContext에 저장
```

토큰에 `sub`(publicId)와 `type`밖에 없으니, **권한을 알아내려고** DB를 봐야 했다.

처음 받은 리뷰는 이걸 "요청 스레드가 DB 조회 1번을 낭비한다"는 처리량 문제로 제기했다.
그 프레이밍은 설득력이 약하다. PK 인덱스 조회 한 번은 비싸지 않다.

**실제로 중요한 프레이밍은 따로 있었다.**

> 캐시로 끝날 수 있는 조회 요청이, 인증 때문에 **DB 커넥션을 잡는다.**

조회 횟수를 1 줄이는 것과 요청 전체를 DB-free로 만드는 것은 질이 다르다.
커넥션 풀이 병목일 때 후자는 의미가 크다. 이 관점이 채택 근거가 됐다.

---

## 2. 왜 "인가 정보를 토큰에 넣기"가 유일한 길인가

"DB 조회를 서비스 계층으로 내리자"와 "토큰에 role/status를 넣자"는 별개 안처럼 들리지만 **같은 안이다.**

- 인가는 `authorizeHttpRequests`와 `@PreAuthorize` 시점에 끝나야 한다 — 즉 필터·메서드 진입 시점.
- 그 시점에 필요한 건 `Collection<GrantedAuthority>`.
- 그 authorities는 `UserStatus` + `UserRole`에서 계산된다.
- 따라서 **role/status를 토큰에서 얻지 못하면 조회를 뺄 수 없다.**

---

## 3. 이득을 실제로 세보기 — 그리고 함정

| 엔드포인트 | 전 | role/status만 토큰에 |
|---|---|---|
| `/auth/status` | 1 | **0** |
| `logout` | 1 | **0** |
| `changePassword` | **2** (필터 + 서비스) | 1 |
| 캐시 히트 목록 조회 | 1 | **0** |
| `user_id` FK가 필요한 쓰기 | 1 | **1** |

**마지막 줄이 함정이다.**

지금 쓰기 API는 `principal.getId()`(내부 PK)를 공짜로 쓴다 — 필터가 조회해줬으니까.
role/status만 토큰에 넣으면 서비스가 `publicId`로 다시 조회해야 해서 **조회 횟수가 그대로**다.
즉 그 API들은 **이득 0인데 즉시성만 잃는다.**

`id`까지 토큰에 넣으면 `entityManager.getReference(User.class, id)`로 select 없이 FK를 세팅할 수 있어 0이 된다.
대신 내부 PK가 클라이언트 손에 간다 — `publicId`를 따로 둔 이유가 절반 무색해지고,
순차 PK라면 "내가 1523번"에서 대략의 가입자 수가 샌다.

**결론: `id`는 넣지 않는다.** 실제로 문제가 되는 건 Phase 8에서 쓰기 API를 만들 때고,
그때 `getReference`가 정말 필요한지 보고 다시 정한다. 지금 넣으면 근거 없이 PK만 노출하는 셈이다.

---

## 4. 지불한 값 — 취소가 최대 30분 늦는다

전에는 매 요청 DB를 읽으니 `BLOCKED`로 바꾸면 **다음 요청부터** 막혔다.
토큰에 넣으면 access TTL(30분)만큼 옛 권한으로 산다.

### 30분 상한이 성립하는 메커니즘

처음에는 "차단 시 Redis refresh 세션을 지우는 것"이 필수 조건이라고 생각했는데, **틀렸다.**
진짜 근거는 **재발급이 DB를 다시 읽는다**는 것이다.

```
차단 → (최대 30분 뒤) 재발급 요청
     → 회전 전에 DB에서 BLOCKED 읽음
     → 403 AUTH-042, 새 토큰 없음
```

세션 삭제는 이걸 앞당길 뿐 필수가 아니다.

### 정지 API 뒤의 범위 (2026-11, A17)

정지(`POST /api/admin/users/{userId}/block`)가 생기면서 지연이 줄었다.

- 정지하면 모든 refresh 세션을 지운다. 로그인·재발급·앱 코드 교환·탈퇴 요청은 403 `AUTH-042`.
- 관리·사진·행사 경로(`SecurityPaths.ACCOUNT_CHECKED`)는 요청마다 역할·상태를 DB에서 다시 본다(`AccountStateInterceptor`). 정지되거나 ADMIN 권한이 빠지면 access 토큰이 살아 있어도 바로 403 `GEN-021`.
- **30분 지연은 그 밖의 일반 API 에만 남는다.** 요청마다 조회하는 비용을 피하려는 선택이고, 전체로 넓힐지는 제품 결정이다.

### 그래서 refresh 토큰에는 role/status를 넣으면 안 된다

넣으면 회전할 때 옛 값을 복사하게 되고, 재발급 시 DB 조회가 사라지면서
**14일 동안 갱신이 멈춘다.** 30분 상한이 통째로 무너진다.

### 최종 그림

```
전:  매 요청마다 DB 조회 1번
후:  30분에 1번 DB 조회
```

**조회가 사라진 게 아니라 빈도가 바뀐 것이다.** 그리고 그 30분마다의 조회가 곧 취소 반영 장치다.

---

## 5. 파생 결정 — principal 타입을 쪼갠다

토큰만으로 `CustomUserPrincipal`을 만들 수 없다.

| 필드 | 토큰에서 얻나 |
|---|---|
| `publicId` | ✅ `sub` |
| `userRole` | ✅ `role` (신규) |
| `userStatus` | ✅ `status` (신규) |
| `id` | ❌ DB에만 |
| `email` | ❌ DB에만 |
| `password` | ❌ DB에만 |

**같은 클래스에 null 세 개를 넣으면** 컴파일도 되고 예외도 안 난다.
Phase 8에서 누가 `principal.getId()`로 FK를 세팅하면 조용히 null이 들어간다.
더 나쁜 건 *"로그인 경로에서 온 principal은 id가 있고 필터 경로에서 온 건 없다"*는 규칙이 생기는데
**타입만 봐선 알 수 없다.**

**타입을 나누면** `AuthenticatedUser`에 `getId()`가 애초에 없어서 컴파일이 안 된다. null을 만날 길이 없다.

### 사실 원래 다른 두 개였다

| | `CustomUserPrincipal` | `AuthenticatedUser` |
|---|---|---|
| 뭐냐 | **로그인 심사표** | **인증된 사람의 신분증** |
| 언제 사나 | 로그인 그 순간만 | 매 요청 |
| 비밀번호 | 있어야 한다 (대조해야 하니까) | **있으면 안 된다** |
| `UserDetails` 구현 | 필수 (`DaoAuthenticationProvider`가 요구) | 불필요 |
| 만드는 곳 | DB | 토큰 |

지금까지 한 클래스였던 건 필터가 DB를 조회한 덕에 *"마침 같은 걸 쓸 수 있었던"* 것이지
원래 같은 개념이어서가 아니다. **DB 조회를 빼는 순간 둘이 다른 것이었다는 게 드러난다.**

`AuthenticatedUser`가 `UserDetails`를 **일부러 구현하지 않는** 이유도 여기 있다.
구현하면 `getPassword()`가 생기고 null을 반환하게 되는데, 그 객체가 비밀번호 검사 경로로 흘러갈 수 있다.
타입에서 막으면 그런 경로가 생길 수 없다.

### authorities 계산은 한 곳에만

권한 계산에 필요한 건 `(role, status)` 둘뿐이고 `AuthenticatedUser`가 정확히 그 조합이다.
그래서 진짜 구현은 거기 두고, `CustomUserPrincipal.getAuthorities()`는 위임한다.

> 중간에 `Authorities` 유틸 클래스를 따로 만들자는 안이 있었는데 **철회했다.**
> 근거로 "두 경로가 갈라지면 인가 버그"라고 했으나, 확인해보니 **로그인 경로의 authorities는 아무도 읽지 않는다** —
> `LoginService`는 `getPublicId()`와 `getUserStatus()`만 꺼내고 `authentication.getAuthorities()`를 보지 않는다.
> 호출부가 사실상 하나뿐인 로직을 미리 빼는 건 근거가 약하다.

---

## 6. 스프링 시큐리티에 이미 있는 표준과 대조

이 구조는 발명품이 아니다. `spring-security-oauth2-resource-server`가 **정확히 같은 일**을 한다.

| 우리가 만든 것 | 표준 |
|---|---|
| `JwtAuthenticationFilter` | `BearerTokenAuthenticationFilter` |
| 쿠키/헤더에서 토큰 꺼내기 | `BearerTokenResolver` |
| `jwtTokenService.parse()` | `JwtDecoder` (`NimbusJwtDecoder`) |
| `JwtClaims` | `Jwt` (`JwtClaimAccessor`) |
| `AuthenticatedUser.from(claims)` | `JwtAuthenticationConverter` |
| `AuthenticatedUser.authorities()` | `JwtGrantedAuthoritiesConverter` |
| `CustomAuthenticationEntryPoint` | `BearerTokenAuthenticationEntryPoint` |

표준 쪽 기본값은 `scope` 클레임 → `SCOPE_xxx` authority이고,
`setAuthoritiesClaimName` / `setAuthorityPrefix`로 바꿀 수 있다.

RFC 7519에 등록된 클레임은 `sub/iss/aud/exp/nbf/iat/jti`뿐이라 `role`·`status`는 **private claim**이다(§4.3).
역할 클레임의 표준 이름은 없고, 실무에선 Keycloak `realm_access.roles`, 그 외 `roles`/`groups` 등으로 갈린다.

### 타입 분리에도 이름이 있다

```java
package org.springframework.security.core;

public interface AuthenticatedPrincipal {
    String getName();
}
```

`UserDetails`는 `UserDetailsService`가 **로그인 심사용으로 적재**하는 것이고,
`AuthenticatedPrincipal`은 **이미 인증이 끝난 주체**다.
`OAuth2AuthenticatedPrincipal`, `OidcUser`, `Saml2AuthenticatedPrincipal`이 전부 후자를 구현하고,
그중 어느 것도 `UserDetails`가 아니다 — 비밀번호가 없으니까.

즉 우리가 "원래 다른 두 개였다"고 내린 결론은 **프레임워크가 이미 내린 결론과 같다.**

실익도 있다. `AbstractAuthenticationToken.getName()`은
`UserDetails` → `AuthenticatedPrincipal` → `java.security.Principal` → `String.valueOf(principal)` 순으로 이름을 뽑는다.
셋 중 아무것도 아니면 레코드의 `toString()`이 이름이 되어 로그·감사에 그대로 찍힌다.
그래서 `AuthenticatedUser`는 `AuthenticatedPrincipal`을 구현한다.

### 우리가 한 트레이드오프에도 이름이 있다

```java
oauth2.jwt(...)           // 자체 검증. 왕복 0. 취소 반영이 TTL만큼 늦다
oauth2.opaqueToken(...)   // 매 요청 인증 서버에 질의(RFC 7662). 즉시 취소. 왕복 1
```

`opaqueToken`이 우리의 "전" 코드다.
**표준에 있는 두 방식 중 하나에서 다른 하나로 옮긴 것**이고,
"짧은 access TTL + refresh로 갱신"이라는 완화책도 표준이 권하는 그대로다.

(`status`는 표준 대응물이 없다. 굳이 대면 `jti` 기반 denylist가 가장 가깝다.)

### 그럼 왜 갈아타지 않았나

1. **토큰 발급은 리소스 서버가 안 해준다.** 우리는 인증 서버 역할도 겸해서 발급·회전·Redis Lua는 그대로 남는다. 얻는 건 검증 쪽 절반뿐.
2. **에러 응답 계약이 이미 있다.** `00-conventions.md`의 봉투와 `AUTH-011`을 맞추려면 결국 엔트리포인트와 `BearerTokenResolver`를 커스텀해야 해서 실속이 준다.
3. 이 프로젝트 목적상 직접 짠 필터를 이해하고 있는 편이 낫다. 단, **표준이 뭘 하는지 알고 같은 모양으로 짠 것**과 모르고 짜서 우연히 같은 것은 다르다.

**다시 볼 시점**: 소셜 로그인이 진짜 OAuth2 흐름을 타거나, 외부 클라이언트에 토큰을 발급하게 될 때.

---

## 7. principal의 생명주기

```
톰캣 스레드 http-nio-8080-exec-7 을 풀에서 꺼냄
│
├─ SecurityContextHolderFilter
│    try {
│      (STATELESS라 복원할 컨텍스트 없음)
│
├────── JwtAuthenticationFilter
│         parse(token) → JwtClaims
│         AuthenticatedUser.from(claims)      ★ 태어남
│         SecurityContextHolder.setContext(ctx)
│              └ ThreadLocal<SecurityContext>
│
├────── AuthorizationFilter / @PreAuthorize
│         SecurityContext에서 꺼내 authorities 검사
│
├────── DispatcherServlet → 컨트롤러
│         @AuthenticationPrincipal 이 같은 인스턴스를 꺼내 줌
│
│    } finally {
│      SecurityContextHolder.clearContext()   ★ 죽음
│    }
│
└─ 스레드를 풀에 반납
```

**요청 종료가 지우는 게 아니라 `SecurityContextHolderFilter`의 `finally`가 지운다.**

톰캣은 스레드를 재사용한다. `exec-7`이 A의 요청을 처리하고 풀로 돌아갔다가 B의 요청을 받는다.
ThreadLocal이 안 지워지면 **B가 A로 인증된 채 처리된다.**
우리 필터가 실패 경로(`reject`)에서 `clearContext()`를 부르는 것도 같은 이유다.

우리 필터는 `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`로 걸려 있어
`SecurityContextHolderFilter`보다 뒤에 온다 — 즉 그 `finally`의 보호 범위 안이다.

### ThreadLocal이라서 생기는 경계

```java
@Async
public void sendMail(...) {
    SecurityContextHolder.getContext().getAuthentication();   // null
}
```

새 스레드로는 안 따라간다. `@Async`, `new Thread`, 병렬 스트림 전부 같다.
필요하면 `DelegatingSecurityContextExecutor`로 감싸거나 인자로 넘겨야 한다.
(`Callable`/`DeferredResult` 같은 서블릿 async는 `WebAsyncManagerIntegrationFilter`가 옮겨준다.)

### 인스턴스는 요청마다 새로

캐시도 공유도 없다. 같은 사용자의 동시 요청 두 개는 서로 다른 인스턴스를 갖는다(레코드라 `equals`는 같다).
그리고 **불변**이다 — 필터가 토큰을 읽은 그 순간의 스냅샷이고,
요청 처리 중 DB에서 차단돼도 이 객체는 안 바뀐다. "최대 30분 지연"이 객체 수준에서는 이렇게 생겼다.

> 하면 안 되는 것: `AuthenticatedUser`를 필드·정적 변수·캐시에 담아두는 것. 요청 수명을 넘기면 낡은 데이터다.

### 전제로 깔린 설정 두 개

- **`SessionCreationPolicy.STATELESS`** — 없으면 SecurityContext가 HTTP 세션에 실려 요청을 넘어 살아남고, 토큰이 진실의 출처라는 전제가 깨진다.
- **`JwtAuthenticationFilter`가 `@Component`가 아님** — `Filter` 타입 빈은 부트가 서블릿 컨테이너 체인에도 자동 등록해 **시큐리티 체인 밖에서 한 번 더** 돈다. 지금은 `SecurityConfig`에서 `new`로 만들어 그 길이 없다.

---

## 8. 구현할 때 밟기 쉬운 지뢰

### ① 클레임이 없을 때 기본값을 주면 안 된다

```java
private <T> T required(Claims payload, String name, Function<String, T> mapper) {
    String raw = payload.get(name, String.class);
    if (raw == null) {
        throw new IllegalArgumentException("missing claim: " + name);
    }
    return mapper.apply(raw);
}
```

claim이 없거나(`null`) 모르는 값이면(`valueOf` 실패) 둘 다 `IllegalArgumentException`이고,
기존 catch가 `INVALID_TOKEN`으로 바꾼다. **없으면 거부**지 `ROLE_USER`로 떨어지지 않는다.
기본값을 주면 claim 하나 지운 토큰으로 권한을 얻는다.

부수 효과로 **배포 전에 발급된 access 토큰은 전부 무효가 된다.** 서비스 전이라 감수한다.

### ② `@AuthenticationPrincipal`은 타입이 안 맞으면 예외가 아니라 `null`이다

```java
if (principal != null && !parameter.getParameterType().isAssignableFrom(principal.getClass())) {
    if (annotation.errorOnInvalidType()) throw new ClassCastException(...);
    return null;                     // ← 기본값
}
```

컨트롤러 시그니처를 하나라도 안 바꾸면 컴파일도 되고 예외도 안 나고 그냥 null이 들어온다.

`TokenController.resolvePublicId`가 정확히 그 지뢰 위에 있다:

```java
if (principal != null) {
    return Optional.of(principal.getPublicId());
}
// ↓ null이면 refresh 쿠키로 폴백
```

타입을 안 바꾸면 로그아웃이 **조용히 refresh 쿠키 경로로만** 동작한다.
refresh 쿠키는 대개 같이 오니 **동작하고 테스트도 통과한다.**
그러다 access 토큰만 있는 요청에서 로그아웃이 소리 없이 아무것도 안 하게 된다.

`errorOnInvalidType = true`로 막고 싶어도 **익명 접근이 가능한 엔드포인트에는 못 쓴다** —
익명일 때 principal이 문자열 `"anonymousUser"`라 터진다. `logout`이 정확히 그런 엔드포인트다.

### ③ 반드시 있어야 하는 테스트

- **role/status 클레임이 없는 토큰이 거부되는가** — ①의 급소
- **`status=BLOCKED`가 실린 토큰으로 요청하면 403인가** — 이 설계가 실제로 작동한다는 유일한 증거
- **정지 계정의 재발급은 회전하지 않고 `AUTH-042` 인가** — 그래서 재발급은 회전 전에 DB를 본다. 서명이 맞는 refresh 토큰만 여기까지 온다

`@WithMockUser`는 스프링의 `User`를 principal로 넣으므로 우리 타입과 안 맞아 ②의 함정에 그대로 걸린다.
이 프로젝트 테스트는 전부 실제 토큰을 발급해 쓰고 있어 그 위험은 없다.
