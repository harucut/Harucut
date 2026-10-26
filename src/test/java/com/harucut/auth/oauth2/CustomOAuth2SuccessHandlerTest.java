package com.harucut.auth.oauth2;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.cookie.CookieProperties;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.oauth2.app.AppLoginFixtures;
import com.harucut.auth.oauth2.app.AppLoginService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomOAuth2SuccessHandler")
class CustomOAuth2SuccessHandlerTest {

    private static final String FRONTEND_URL = "http://localhost:5173";
    private static final String PUBLIC_ID = "abc123456789";
    private static final String APP_CALLBACK = "harucut://oauth2/callback";

    private static final IssuedToken ACCESS = new IssuedToken("access-token-value", Duration.ofMinutes(30));
    private static final IssuedToken REFRESH = new IssuedToken("refresh-token-value", Duration.ofDays(14));

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private AppLoginService appLoginService;

    private CustomOAuth2SuccessHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new CustomOAuth2SuccessHandler(
                jwtTokenService,
                refreshTokenService,
                new CookieManager(new CookieProperties("localhost", false, "Lax")),
                appLoginService,
                FRONTEND_URL,
                APP_CALLBACK);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Nested
    @DisplayName("토큰 발급")
    class TokenIssuing {

        @Test
        @DisplayName("액세스 토큰을 우리 DB의 publicId·role·status로 만든다")
        void accessTokenUsesOurClaims() throws IOException {
            givenTokens();

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            then(jwtTokenService).should()
                    .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("리프레시 토큰을 저장소에 남긴다")
        void savesRefreshToken() throws IOException {
            givenTokens();

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            then(refreshTokenService).should().save(PUBLIC_ID, REFRESH);
        }
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        @DisplayName("쿠키 두 개를 내려보낸다")
        void setsBothCookies() throws IOException {
            givenTokens();

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                    .hasSize(2)
                    .anySatisfy(cookie -> assertThat(cookie).startsWith("accessToken=access-token-value"))
                    .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=refresh-token-value"));
        }

        @Test
        @DisplayName("쿠키는 HttpOnly로 나간다")
        void cookiesAreHttpOnly() throws IOException {
            givenTokens();

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                    .allSatisfy(cookie -> assertThat(cookie).contains("HttpOnly"));
        }

        /*
         * JSON 이 아니라 302 다. 프론트가 콜백 페이지에서 GET /api/auth/status 로 로그인 완료를 확인한다.
         */
        @Test
        @DisplayName("프론트 콜백으로 302 리다이렉트한다")
        void redirectsToFrontendCallback() throws IOException {
            givenTokens();

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_URL + "/oauth2/callback");
        }

        /*
         * 리다이렉트 목적지는 설정에서만 온다. 요청 파라미터에서 읽으면 오픈 리다이렉트가 된다 —
         * 쿠키를 붙인 채 공격자 사이트로 보내는 것과 같다.
         */
        @Test
        @DisplayName("요청 파라미터로 리다이렉트 목적지를 바꿀 수 없다")
        void ignoresRedirectParameter() throws IOException {
            givenTokens();
            request.setParameter("redirect_uri", "https://evil.example.com");

            handler.onAuthenticationSuccess(request, response, authentication(oAuth2User()));

            assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_URL + "/oauth2/callback");
        }
    }

    /*
     * CustomOidcUser 는 CustomOAuth2User 를 상속한다. 캐스팅 하나로 구글·카카오(OIDC)와
     * 네이버(OAuth2)를 다 받는다는 뜻이고, 이게 깨지면 OIDC 쪽만 조용히 500 이 된다.
     */
    @Test
    @DisplayName("OIDC 사용자도 같은 경로를 탄다")
    void handlesOidcUser() throws IOException {
        givenTokens();

        handler.onAuthenticationSuccess(request, response, authentication(oidcUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_URL + "/oauth2/callback");
        then(refreshTokenService).should().save(PUBLIC_ID, REFRESH);
    }

    @Nested
    @DisplayName("앱 로그인(client=app)")
    class AppLogin {

        @Test
        @DisplayName("쿠키 없이 일회용 코드를 앱 스킴으로 — 시스템 브라우저의 쿠키는 WebView 로 넘어가지 않는다")
        void redirectsWithCode() throws IOException {
            given(appLoginService.issue(PUBLIC_ID, AppLoginFixtures.CHALLENGE)).willReturn("one-time");
            MockHttpServletRequest callback = AppLoginFixtures.callback(Map.of("client", "app", "code_challenge", AppLoginFixtures.CHALLENGE));

            handler.onAuthenticationSuccess(callback, response, authentication(oidcUser()));

            assertThat(response.getRedirectedUrl()).isEqualTo(APP_CALLBACK + "?code=one-time");
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
            then(refreshTokenService).should(never()).save(any(), any());
        }

        @Test
        @DisplayName("PKCE 값이 없으면 코드를 주지 않고 error=invalid_request")
        void noChallenge() throws IOException {
            handler.onAuthenticationSuccess(AppLoginFixtures.callback(Map.of("client", "app")), response, authentication(oAuth2User()));

            assertThat(response.getRedirectedUrl()).isEqualTo(APP_CALLBACK + "?error=invalid_request");
            then(appLoginService).should(never()).issue(any(), any());
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        }
    }

    private void givenTokens() {
        given(jwtTokenService.createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE))
                .willReturn(ACCESS);
        given(jwtTokenService.createRefreshToken(PUBLIC_ID)).willReturn(REFRESH);
    }

    private static OAuth2AuthenticationToken authentication(CustomOAuth2User principal) {
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private static CustomOAuth2User oAuth2User() {
        DefaultOAuth2User delegate = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("PROVIDER")),
                Map.of("sub", "google-sub-1"),
                "sub");
        return new CustomOAuth2User(delegate, authenticatedUser());
    }

    private static CustomOAuth2User oidcUser() {
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of(IdTokenClaimNames.SUB, "google-sub-1"));
        DefaultOidcUser delegate = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("PROVIDER")), idToken);
        return new CustomOidcUser(delegate, authenticatedUser());
    }

    private static AuthenticatedUser authenticatedUser() {
        return new AuthenticatedUser(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE);
    }
}
