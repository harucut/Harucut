package com.harucut.config.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    public static final String COOKIE_SCHEME = "cookieAuth";
    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String PARTICIPANT_SCHEME = "participantToken";
    public static final String BOOTH_SCHEME = "boothToken";
    public static final String RESULT_SCHEME = "resultAccess";

    private static final String ACCESS_TOKEN_COOKIE = "accessToken";

    @Bean
    public OpenAPI harucutOpenAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(info())
                .tags(tags())
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("로컬 개발"),
                        new Server().url("https://dev.harucut.com").description("스테이징"),
                        new Server().url("https://api.harucut.com").description("운영")))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_SCHEME, cookieScheme())
                        .addSecuritySchemes(BEARER_SCHEME, bearerScheme())
                        .addSecuritySchemes(PARTICIPANT_SCHEME, headerScheme("X-Participant-Token",
                                "행사 참가자 세션 토큰. `POST /api/public/events/{slug}/sessions` 응답의 token 원문을 그대로 싣는다. 쿠키가 아니다."))
                        .addSecuritySchemes(BOOTH_SCHEME, headerScheme("X-Booth-Token",
                                "등록된 부스 기기의 토큰. 페어링 교환 응답에서 한 번만 받는다."))
                        .addSecuritySchemes(RESULT_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("결과 토큰. 결과 링크 `/r#<token>` 의 fragment 를 `Authorization: Bearer <token>` 으로 싣는다.")));

        return openAPI;
    }

    /**
     * 태그는 <b>컨트롤러가 아니라 도메인</b> 기준이다. 프론트에게 {@code auth-controller} 와
     * {@code auth-status-controller} 가 따로 보이는 건 백엔드 사정일 뿐이다.
     *
     * <p>여기 적은 <b>순서가 곧 Swagger UI 의 표시 순서</b>다(그래서 {@code tags-sorter} 를 쓰지 않는다).
     * 가나다순이 아니라 프론트가 화면을 만들어 가는 순서로 놓았다 —
     * 로그인해서 들어오고, 내 정보를 읽고, 파일을 올리고, 프레임을 만들고, 사진을 뽑고, 결제를 한다.
     *
     * <p>인증만 여섯으로 쪼갠 이유: 엔드포인트가 14개인데다 이메일 인증·비밀번호·토큰·탈퇴가
     * 프론트에서 각각 다른 화면이다. 한 덩어리로 두면 찾는 비용이 더 크다.
     */
    private List<Tag> tags() {
        return List.of(
                tag("인증 · 이메일 인증", "가입 전 이메일 소유 확인. 코드 발송 → 검증"),
                tag("인증 · 가입 · 로그인", "이메일 가입과 로그인. 로그인 성공 시 토큰 2종이 쿠키로 내려간다"),
                tag("인증 · 토큰", "액세스 토큰 재발급과 로그아웃. **재발급 요청은 프론트에서 직렬화해야 한다**"),
                tag("인증 · 비밀번호", "비밀번호 재설정(로그인 전)과 변경(로그인 후)"),
                tag("인증 · 상태", "현재 로그인 상태와 사용자 상태 조회. 탈퇴 요청 상태를 여기서 확인한다"),
                tag("인증 · 탈퇴 · 복구", "탈퇴 요청과 취소. 요청 후 7일간은 되돌릴 수 있다"),
                tag("내 정보", "프로필 조회, 닉네임·프로필 이미지 변경, 요금제 사용량"),
                tag("약관", "활성 약관 조회와 동의·철회"),
                tag("공지", "게시된 공지 목록과 단건"),
                tag("공간", "프레임·자산·초안·행사가 속하는 공간. 개인 공간과 조직 공간"),
                tag("공간 멤버", "조직 공간의 멤버 초대·역할·회수, 초대 미리보기·수락, 감사 로그"),
                tag("행사 고객·권한", "행사 고객 초대와 행사 사진 권한(EventGrant). 공간 역할만으로는 사진을 보지 않는다"),
                tag("행사 운영", "운영자(조직 공간 OWNER·ADMIN) — 행사 만들기·설정·게시·일시중지·촬영 종료"),
                tag("행사 참가", "로그인 없는 참가자 — 행사 조회 → 세션 → 동의 → 회차 → 촬영 업로드 → 합성 → 결과 토큰. 자격은 `X-Participant-Token` 헤더"),
                tag("부스", "행사장 공용 촬영 기기 — 연결 코드 교환 → 기기 토큰(`X-Booth-Token`) → 다음 손님 세션·회차 확인·닫기. 촬영 자체는 손님의 참가자 토큰으로"),
                tag("행사 결과 수령", "결과 링크(`/r#<token>`)로 여는 결과 상태·다운로드. 자격은 Bearer 결과 토큰"),
                tag("자산", "공통 이미지 자산 — 업로드 의도 → S3 PUT → 완료 검증. VERIFIED 만 프레임에 쓴다"),
                tag("파일 업로드", "S3 presigned URL 발급. 업로드는 프론트가 S3로 직접 한다"),
                tag("프레임", "네컷 사진의 틀. 만들고 저장하고 불러온다"),
                tag("프레임 초안", "편집기 문서의 서버 초안. revision 이 같을 때만 저장된다"),
                tag("네컷 합성", "원본 사진 + 프레임 → 완성 이미지. 접수(202) 후 폴링한다"),
                tag("사진 보관함", "합성이 끝난 내 사진. 목록·다운로드·이름변경·삭제"),
                tag("구독", "내 요금제 조회와 자동갱신 해지"),
                tag("결제", "빌링키 기반 구독 결제와 결제 내역"),
                tag("쿠폰", "쿠폰 사용과 내 쿠폰 목록"),
                tag("공지 관리", "관리자 — 공지 작성·수정·게시·삭제"),
                tag("약관 관리", "관리자 — 약관 등록과 버전 개정"),
                tag("시스템 프레임 관리", "관리자 — 모든 사용자에게 제공되는 기본 프레임"),
                tag("감사 로그 관리", "관리자 — 전체 운영 행동 기록"),
                tag("구독 관리", "관리자 — 특정 사용자의 구독 조회"),
                tag("쿠폰 관리", "관리자 — 쿠폰 발행·집계·비활성화"),
                tag("결제 웹훅", "PG사가 호출한다. 프론트가 부르는 API 가 아니다"),
                tag("소셜 연동 해제", "네이버가 호출한다. 프론트가 부르는 API 가 아니다"));
    }

    private Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    /**
     * 운영에서 실제로 쓰는 인증 경로. httpOnly 쿠키라 Swagger UI 의 Authorize 로는 넣을 수 없지만,
     * <b>프론트가 무엇을 보내야 하는지를 문서가 말해야 하므로</b> 스킴으로 남긴다.
     * 기존 코틀린 구현은 bearerAuth 만 등록해서, 문서와 실제 인증 경로가 달랐다.
     */
    private SecurityScheme cookieScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(ACCESS_TOKEN_COOKIE)
                .description("운영 인증 경로. 로그인 응답의 `Set-Cookie: accessToken=...` 을 브라우저가 자동으로 싣는다.");
    }

    private SecurityScheme headerScheme(String header, String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(header)
                .description(description);
    }

    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Swagger UI 테스트용 대체 경로. 서버는 쿠키를 먼저 보고, 없을 때만 이 헤더를 본다.");
    }

    /**
     * 그룹을 나누는 이유: 프론트는 관리자 API 19개를 볼 일이 없다. 경로 접두어가 이미
     * {@code /api/admin/**} 으로 갈려 있어 비용이 거의 없다.
     */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("1-user")
                .displayName("사용자 API")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/admin/**", "/api/payments/webhook", "/api/oauth2/unlink/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("2-admin")
                .displayName("관리자 API")
                .pathsToMatch("/api/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi webhookApi() {
        return GroupedOpenApi.builder()
                .group("3-webhook")
                .displayName("웹훅 (외부 시스템이 호출)")
                .pathsToMatch("/api/payments/webhook", "/api/oauth2/unlink/**")
                .build();
    }

    private Info info() {
        return new Info()
                .title("Harucut API")
                .version("v1")
                .description("""
                        하루컷 백엔드 API 문서.

                        ## 1. 모든 응답은 같은 봉투에 담긴다

                        ```json
                        { "code": "GEN-000", "status": 200, "data": { ... } }
                        ```

                        | 필드 | 설명 |
                        |------|------|
                        | `code` | 성공은 항상 `GEN-000`. 실패는 도메인별 에러 코드 |
                        | `status` | HTTP 상태 코드 (바디에도 넣어 한 곳만 봐도 되게 했다) |
                        | `message` | **실패일 때만** 실린다. 성공 응답에는 키 자체가 없다 |
                        | `data` | 본문. 없으면 키 자체가 없다 |

                        `null` 필드는 직렬화에서 빠진다. **키가 없는 것과 `null` 인 것을 구분하지 말 것.**

                        ## 2. 분기는 HTTP 상태가 아니라 `code` 로 한다

                        같은 403 안에 `GEN-021`(권한 없음), `SUBS-002`(보관 기간 초과), `SUBS-003`(개수 초과)이
                        함께 들어온다. 각 API 의 응답 섹션에서 **Examples 드롭다운**을 열면 그 엔드포인트가 낼 수 있는
                        코드가 전부 나오고, 각각의 실제 응답 JSON 을 볼 수 있다.

                        ## 3. 인증

                        | | 무엇 |
                        |---|---|
                        | **운영** | httpOnly `accessToken` 쿠키. 프론트는 `credentials: 'include'` 만 하면 된다 |
                        | **Swagger 테스트** | `Authorization: Bearer <JWT>` — 우측 상단 **Authorize** |

                        서버는 **쿠키를 먼저 보고, 없을 때만 헤더를 본다.**
                        ⚠️ 그래서 이 페이지에서 로그인을 한 번 하면 브라우저에 `accessToken` 쿠키가 남아,
                        그 뒤로는 Authorize 에 넣은 Bearer 토큰이 **무시된다.** 401 이 계속 나면 쿠키부터 지울 것.

                        토큰 얻는 법: `POST /api/harucut/login` 실행 → 응답 헤더의 `Set-Cookie: accessToken=<JWT>` 에서 값 복사.

                        ## 4. 문서에 안 보이는 것

                        `/oauth2/authorization/{google|kakao|naver}` 는 Security 필터가 처리하는 브라우저 리다이렉트라
                        여기 나오지 않고 **Try it out 으로도 테스트할 수 없다.** 브라우저에서 직접 접속해 로그인하면
                        쿠키가 발급된다.
                        """);
    }
}
