package com.harucut.auth.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomOAuth2FailureHandler")
class CustomOAuth2FailureHandlerTest {

    private static final String FRONTEND_URL = "http://localhost:5173";
    private static final String EXPECTED_URL = FRONTEND_URL + "/oauth2/callback?error=oauth2";

    private CustomOAuth2FailureHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new CustomOAuth2FailureHandler(FRONTEND_URL, "harucut://oauth2/callback");
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("프론트 콜백으로 error=oauth2를 붙여 302 리다이렉트한다")
    void redirectsWithError() throws IOException {
        handler.onAuthenticationFailure(request, response, exception("invalid_state"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(EXPECTED_URL);
    }

    /*
     * 실패 이유를 URL 에 싣지 않는다. 리다이렉트 URL 은 브라우저 기록·Referer·프록시 로그에 남는다.
     * 프론트는 error 파라미터의 존재만 보고 분기한다 (docs/01-auth.md 6절).
     */
    @Test
    @DisplayName("실패 이유가 달라도 응답은 같다")
    void doesNotLeakReason() throws IOException {
        handler.onAuthenticationFailure(request, response, exception("missing_required_user_info"));

        assertThat(response.getRedirectedUrl()).isEqualTo(EXPECTED_URL);
    }

    @Test
    @DisplayName("응답 본문이 없다 — JSON 봉투를 쓰지 않는다")
    void hasNoBody() throws IOException {
        handler.onAuthenticationFailure(request, response, exception("invalid_state"));

        assertThat(response.getContentAsString()).isEmpty();
    }

    private static AuthenticationException exception(String errorCode) {
        return new OAuth2AuthenticationException(new OAuth2Error(errorCode), errorCode);
    }

    @Test
    @DisplayName("앱에서 시작한 로그인이 실패·취소되면 앱 스킴으로 error=oauth2")
    void appLoginFailure() throws IOException {
        MockHttpServletRequest callback = com.harucut.auth.oauth2.app.AppLoginFixtures.callback(
                java.util.Map.of("client", "app", "code_challenge", com.harucut.auth.oauth2.app.AppLoginFixtures.CHALLENGE));

        handler.onAuthenticationFailure(callback, response, exception("access_denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("harucut://oauth2/callback?error=oauth2");
    }
}
