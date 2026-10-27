package com.harucut.config;

import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;

/**
 * 보안 필터 체인의 경로 규칙. <b>인가 규칙과 OpenAPI 문서가 같은 목록을 읽게</b> 하려고 밖으로 뺐다.
 *
 * <p>코틀린 구현은 컨트롤러마다 {@code @SecurityRequirement} 를 손으로 붙였고, 그 방식은 경로를
 * 열거나 잠글 때 문서 쪽을 같이 안 고치면 조용히 어긋난다. 여기서는 문서가 이 클래스에서 파생된다.
 *
 * <p>{@code contains} 계열은 실제 요청 경로가 아니라 OpenAPI 문서의 <b>경로 템플릿</b>
 * ({@code /api/notices/{publicId}})을 받는다. {@code {publicId}} 는 그냥 한 세그먼트로 취급되므로
 * {@code /api/notices/**} 패턴에 그대로 걸린다.
 */
public final class SecurityPaths {

    /** 인증 없이 열어두는 경로 ({@code permitAll}). */
    public static final String[] PUBLIC = {
            "/api/harucut/register",
            "/api/harucut/login",
            "/api/harucut/reissue",
            "/api/harucut/logout",
            // 앱 로그인 코드 교환 — 코드·PKCE 검증값이 자격이다
            "/api/harucut/oauth2/exchange",
            "/api/harucut/reset/password/code",
            "/api/harucut/reset/password",
            "/api/harucut/reset/password/verification",
            "/api/email-auth/**",
            "/api/notices/**",
            "/oauth2/**",
            "/login/oauth2/**",
            "/api/oauth2/unlink/naver",
            "/api/terms",
            "/api/payments/webhook",
            // 스토어 알림 — 애플은 JWS 서명, 구글은 푸시 URL 토큰으로 확인한다(ADR-0011 §2)
            "/api/payments/webhooks/app-store",
            "/api/payments/webhooks/google-play",
            "/api/public/inquiries",
            // 자유 문구 스타일 목록(정적 카탈로그)
            "/api/public/text-styles",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/error"
    };

    /**
     * 플랫폼 관리자 경로 ({@code hasRole("ADMIN")}). 컨트롤러마다 붙인 {@code @PreAuthorize} 와 별개로
     * URL 수준에서 한 번 더 막는다 — 새 관리자 컨트롤러가 애노테이션을 빠뜨려도 일반 사용자에게 열리지 않는다.
     */
    public static final String ADMIN = "/api/admin/**";

    /**
     * 로그인만 되어 있으면 되는 경로 ({@code authenticated}). 나머지 경로는 전부
     * {@code hasAnyRole("USER","ADMIN")} 이라 탈퇴 요청·정지 상태에서 403 이 난다.
     *
     * <p>여기 있는 경로는 <b>역할을 보지 않으므로 그 403 이 나지 않는다.</b>
     * 탈퇴 요청 상태의 사용자가 자기 상태를 읽고 복구까지 갈 수 있어야 하기 때문이다.
     * 단, 메서드에 {@code @PreAuthorize} 가 따로 붙어 있으면 그쪽에서 403 이 날 수 있다
     * (복구 API 가 그렇다).
     */
    public static final String[] AUTHENTICATED_ONLY = {
            "/api/auth/status",
            "/api/harucut/reactivate",
            // 로그아웃 직전의 푸시 기기 해제 — 탈퇴 요청·정지 상태에서도 기기를 떼어 낼 수 있어야 한다
            "/api/auth/devices/unregister"
    };

    /**
     * 행사 참가·부스·결과 경로 — 회원 체인이 아니라 {@code EventSecurityConfig} 의 전용 체인이 받는다.
     * 자격은 쿠키가 아니라 헤더다(참가자 토큰·부스 토큰·결과 토큰).
     */
    public static final String[] EVENT = {
            "/api/public/events/**", "/api/participant/**", "/api/booth/**",
            "/api/public/booth-pairings/**", "/api/public/results/**"
    };

    /**
     * 쿠키 인증 변경 요청의 출처 검사(OriginCheckFilter)에서 빼는 경로. 다른 사이트(결제사·스토어·소셜 제공자)가
     * 보내는 게 정상인 콜백·웹훅과, 자격을 본문으로 받는 앱 코드 교환이다. 행사 경로는 체인이 따로라 여기 없어도 대상이 아니다.
     */
    public static final String[] ORIGIN_CHECK_EXEMPT = {
            "/api/payments/webhook",
            "/api/payments/webhooks/**",
            "/oauth2/**",
            "/login/oauth2/**",
            "/api/oauth2/unlink/naver",
            "/api/harucut/oauth2/exchange"
    };

    /** 행사 체인 안에서 자격 없이 여는 경로(행사 조회·BYOD 세션 발급·부스 페어링 교환). */
    public static final String[] EVENT_OPEN = {"/api/public/events/**", "/api/public/booth-pairings/**"};

    private static final List<PathPattern> PARSED_PUBLIC = parse(PUBLIC);
    private static final List<PathPattern> PARSED_EVENT = parse(EVENT);
    private static final List<PathPattern> PARSED_EVENT_OPEN = parse(EVENT_OPEN);
    private static final List<PathPattern> PARSED_AUTHENTICATED_ONLY = parse(AUTHENTICATED_ONLY);
    private static final List<PathPattern> PARSED_ORIGIN_CHECK_EXEMPT = parse(ORIGIN_CHECK_EXEMPT);

    private SecurityPaths() {
    }

    /** Security 필터 체인이 쓰는 공개 경로 매처. */
    public static RequestMatcher publicMatcher() {
        return new OrRequestMatcher(
                Arrays.stream(PUBLIC)
                        .map(PathPatternRequestMatcher::pathPattern)
                        .toArray(RequestMatcher[]::new));
    }

    /** 출처 검사 제외 경로 매처. */
    public static RequestMatcher originCheckExemptMatcher() {
        return new OrRequestMatcher(
                Arrays.stream(ORIGIN_CHECK_EXEMPT)
                        .map(PathPatternRequestMatcher::pathPattern)
                        .toArray(RequestMatcher[]::new));
    }

    public static boolean isPublic(String pathTemplate) {
        return matches(PARSED_PUBLIC, pathTemplate);
    }

    public static boolean isEvent(String pathTemplate) {
        return matches(PARSED_EVENT, pathTemplate);
    }

    public static boolean isEventOpen(String pathTemplate) {
        return matches(PARSED_EVENT_OPEN, pathTemplate);
    }

    public static boolean isAuthenticatedOnly(String pathTemplate) {
        return matches(PARSED_AUTHENTICATED_ONLY, pathTemplate);
    }

    public static boolean isOriginCheckExempt(String pathTemplate) {
        return matches(PARSED_ORIGIN_CHECK_EXEMPT, pathTemplate);
    }

    private static List<PathPattern> parse(String[] patterns) {
        return Arrays.stream(patterns).map(PathPatternParser.defaultInstance::parse).toList();
    }

    private static boolean matches(List<PathPattern> patterns, String pathTemplate) {
        PathContainer container = PathContainer.parsePath(pathTemplate);
        return patterns.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
