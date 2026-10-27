package com.harucut.auth.security;

import com.harucut.config.SecurityPaths;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("쿠키 인증 변경 요청의 출처 검사")
class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter(
            List.of("https://harucut.com", "http://localhost:5173"),
            SecurityPaths.originCheckExemptMatcher(), JsonMapper.builder().build());

    @Test
    @DisplayName("허용 Origin 은 통과")
    void allowedOrigin() throws Exception {
        assertThat(run(cookiePost("/api/frames").origin("https://harucut.com"))).isTrue();
        assertThat(run(cookiePost("/api/frames").origin("http://localhost:5173"))).isTrue();
    }

    @Test
    @DisplayName("Origin 은 대소문자를 가리지 않는다")
    void originCaseInsensitive() throws Exception {
        assertThat(run(cookiePost("/api/frames").origin("https://HARUCUT.com"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "null", "https://harucut.com.evil.example", "http://harucut.com",
            "https://harucut.com:8443"})
    @DisplayName("목록 밖 Origin 은 403 GEN-022")
    void foreignOrigin(String origin) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(cookiePost("/api/frames").origin(origin), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"GEN-022\"");
    }

    @Test
    @DisplayName("이 서버와 같은 출처의 Origin 은 목록에 없어도 통과(같은 출처는 위조 요청이 아니다)")
    void sameOrigin() throws Exception {
        MockHttpServletRequest request = cookiePost("/api/frames").origin("https://api.harucut.com").build();
        request.setScheme("https");
        request.setServerName("api.harucut.com");
        request.setServerPort(443);
        assertThat(run(request)).isTrue();
    }

    @Test
    @DisplayName("Origin 이 없으면 Referer 의 출처로 판정한다")
    void refererFallback() throws Exception {
        assertThat(run(cookiePost("/api/frames").referer("https://harucut.com/frames/1?x=y"))).isTrue();
        assertThat(run(cookiePost("/api/frames").referer("https://evil.example/harucut.com"))).isFalse();
        assertThat(run(cookiePost("/api/frames").referer("not a url"))).isFalse();
    }

    @Test
    @DisplayName("Origin 이 있으면 Referer 는 보지 않는다")
    void originWinsOverReferer() throws Exception {
        assertThat(run(cookiePost("/api/frames").origin("https://evil.example").referer("https://harucut.com/"))).isFalse();
    }

    @Test
    @DisplayName("Origin·Referer 둘 다 없으면 통과(서버 간 호출·앱)")
    void neitherPasses() throws Exception {
        assertThat(run(cookiePost("/api/frames"))).isTrue();
        assertThat(run(cookiePost("/api/frames").origin("").referer(" "))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    @DisplayName("POST 외 변경 메서드도 대상")
    void otherUnsafeMethods(String method) throws Exception {
        MockHttpServletRequest request = cookiePost("/api/frames").origin("https://evil.example").build();
        request.setMethod(method);
        assertThat(run(request)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    @DisplayName("읽기 메서드는 대상이 아니다")
    void safeMethods(String method) throws Exception {
        MockHttpServletRequest request = cookiePost("/api/frames").origin("https://evil.example").build();
        request.setMethod(method);
        assertThat(run(request)).isTrue();
    }

    @Test
    @DisplayName("인증 쿠키가 없으면 대상이 아니다")
    void noAuthCookie() throws Exception {
        MockHttpServletRequest request = new Req("/api/frames").origin("https://evil.example").build();
        request.setCookies(new Cookie("other", "x"), new Cookie("accessToken", ""));
        assertThat(run(request)).isTrue();
    }

    @Test
    @DisplayName("refreshToken 쿠키만 있어도 대상")
    void refreshCookieOnly() throws Exception {
        MockHttpServletRequest request = new Req("/api/harucut/reissue").origin("https://evil.example").build();
        request.setCookies(new Cookie("refreshToken", "r"));
        assertThat(run(request)).isFalse();
    }

    @Test
    @DisplayName("Authorization 헤더가 있으면 대상이 아니다(다른 출처는 preflight 없이 실을 수 없다)")
    void authorizationHeader() throws Exception {
        MockHttpServletRequest request = cookiePost("/api/frames").origin("https://evil.example").build();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        assertThat(run(request)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/payments/webhook", "/api/payments/webhooks/app-store",
            "/api/payments/webhooks/google-play", "/login/oauth2/code/apple", "/oauth2/authorization/google",
            "/api/oauth2/unlink/naver", "/api/harucut/oauth2/exchange"})
    @DisplayName("웹훅·OAuth 콜백·앱 코드 교환은 뺀다")
    void exempt(String path) throws Exception {
        assertThat(run(cookiePost(path).origin("https://evil.example"))).isTrue();
    }

    private static Req cookiePost(String path) {
        return new Req(path).cookie();
    }

    private boolean run(Req req) throws Exception {
        return run(req.build(), new MockHttpServletResponse());
    }

    private boolean run(MockHttpServletRequest request) throws Exception {
        return run(request, new MockHttpServletResponse());
    }

    private boolean run(Req req, MockHttpServletResponse response) throws Exception {
        return run(req.build(), response);
    }

    /** 다음 필터로 넘어갔으면 true. */
    private boolean run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    private static final class Req {
        private final MockHttpServletRequest request;

        Req(String path) {
            request = new MockHttpServletRequest("POST", path);
            request.setServletPath(path);
        }

        Req cookie() {
            request.setCookies(new Cookie("accessToken", "a"));
            return this;
        }

        Req origin(String origin) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
            return this;
        }

        Req referer(String referer) {
            request.addHeader(HttpHeaders.REFERER, referer);
            return this;
        }

        MockHttpServletRequest build() {
            return request;
        }
    }
}
