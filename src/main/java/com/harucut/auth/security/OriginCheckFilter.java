package com.harucut.auth.security;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.Response;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * 쿠키 인증 변경 요청의 출처 검사 — CSRF 방어(R5 D07). 스프링 CSRF 토큰 대신 Origin 을 본다.
 *
 * <p>대상: POST·PUT·PATCH·DELETE 이면서 Authorization 헤더가 없고 인증 쿠키(accessToken·refreshToken)가 실린 요청.
 * 헤더 자격은 다른 사이트가 preflight(CORS) 없이 실을 수 없으니 대상이 아니다. 웹훅·OAuth 콜백처럼
 * 다른 사이트에서 오는 게 정상인 경로는 {@code excluded} 로 뺀다.
 *
 * <ol>
 *   <li>Origin 이 있으면 허용 목록에 있거나 이 서버와 같은 출처여야 통과("null" 포함, 그 밖이면 403).</li>
 *   <li>Origin 이 없으면 Referer 의 출처로 같은 판정.</li>
 *   <li>둘 다 없으면 통과 — 서버 간 호출(BFF)·앱은 둘 다 싣지 않는다. 브라우저는 교차 출처 변경 요청에 Origin 을 싣는다.</li>
 * </ol>
 *
 * <p>CorsFilter 보다 앞에 둔다. 목록 밖 Origin 은 CORS 처리기도 403("Invalid CORS request", 본문이 JSON 아님)으로
 * 막지만, 쿠키 요청이면 여기서 먼저 GEN-022 로 거절해 응답 형식과 로그를 한 곳에 모은다.
 */
@Slf4j
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> AUTH_COOKIES = Set.of(CookieManager.ACCESS_TOKEN, CookieManager.REFRESH_TOKEN);

    private final Set<String> allowedOrigins;
    private final RequestMatcher excluded;
    private final ObjectMapper objectMapper;

    public OriginCheckFilter(Collection<String> allowedOrigins, RequestMatcher excluded, ObjectMapper objectMapper) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.excluded = excluded;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UNSAFE_METHODS.contains(request.getMethod())
                || StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))
                || !hasAuthCookie(request)
                || excluded.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = blankToNull(request.getHeader(HttpHeaders.ORIGIN));
        String referer = blankToNull(request.getHeader(HttpHeaders.REFERER));
        String source = origin != null ? origin : originOf(referer);

        boolean sameOrigin = origin != null && !CorsUtils.isCorsRequest(request);
        if (source == null || sameOrigin || allowedOrigins.contains(source.toLowerCase(Locale.ROOT))) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[출처 검사] 거절 method={} uri={} origin={} refererOrigin={}", request.getMethod(),
                request.getRequestURI(), origin, origin == null ? source : null);
        response.setStatus(GlobalErrorCode.CROSS_ORIGIN_REJECTED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Response.error(GlobalErrorCode.CROSS_ORIGIN_REJECTED));
    }

    private static boolean hasAuthCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies)
                .anyMatch(c -> AUTH_COOKIES.contains(c.getName()) && StringUtils.hasText(c.getValue()));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /**
     * Referer 에서 scheme://host[:port] 만 뽑는다. 없으면 null(판정 안 함), 읽을 수 없으면 빈 문자열(목록 밖 → 거절).
     */
    static String originOf(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
