package com.harucut.auth.oauth2.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static com.harucut.auth.oauth2.app.AppLoginFixtures.CHALLENGE;
import static com.harucut.auth.oauth2.app.AppLoginFixtures.callback;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("앱 로그인 표시 — 시작 요청에서 콜백까지")
class AppLoginRequestTest {

    @Test
    @DisplayName("client=app·S256 값이면 콜백에서 그 값을 읽는다")
    void carriesChallenge() {
        assertThat(AppLoginRequest.of(callback(Map.of("client", "app", "code_challenge", CHALLENGE))))
                .contains(new AppLoginRequest(CHALLENGE));
        assertThat(AppLoginRequest.of(callback(Map.of("client", "app", "code_challenge", CHALLENGE, "code_challenge_method", "S256"))))
                .contains(new AppLoginRequest(CHALLENGE));
    }

    @Test
    @DisplayName("앱 진입이지만 PKCE 값이 없거나·모양이 틀리거나·plain 이면 값 없이 표시만 — 코드를 주지 않게")
    void appWithoutValidChallenge() {
        assertThat(AppLoginRequest.of(callback(Map.of("client", "app")))).contains(new AppLoginRequest(null));
        assertThat(AppLoginRequest.of(callback(Map.of("client", "app", "code_challenge", "short")))).contains(new AppLoginRequest(null));
        assertThat(AppLoginRequest.of(callback(Map.of("client", "app", "code_challenge", CHALLENGE, "code_challenge_method", "plain"))))
                .contains(new AppLoginRequest(null));
    }

    @Test
    @DisplayName("웹 진입(표시 없음·다른 값)이나 인가 요청을 꺼내지 않은 요청은 앱이 아니다")
    void webLogin() {
        assertThat(AppLoginRequest.of(callback(Map.of()))).isEmpty();
        assertThat(AppLoginRequest.of(callback(Map.of("client", "web", "code_challenge", CHALLENGE)))).isEmpty();
        assertThat(AppLoginRequest.of(new MockHttpServletRequest())).isEmpty();
    }
}
