package com.harucut.auth.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.dto.AuthTokenCookies;
import com.harucut.auth.jwt.JwtProperties;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.config.SecurityConfig;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@WebMvcTest(TokenController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, CookieManager.class, FixedClockConfig.class})
@ActiveProfiles("test")
class TokenControllerTest extends SecurityBeansMockSupport {

    private static final String REISSUE_URI = "/api/harucut/reissue";
    private static final String LOGOUT_URI = "/api/harucut/logout";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Clock clock;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @Nested
    @DisplayName("POST /reissue")
    class Reissue {

        @Test
        @DisplayName("refresh 쿠키가 없으면 400 GEN-004이고 서비스를 부르지 않는다")
        void missingCookie() {
            assertThat(mockMvc.post().uri(REISSUE_URI))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-004"));

            then(refreshTokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("정상 재발급이면 200과 새 쿠키 2개가 내려간다")
        void success() {
            given(refreshTokenService.reissue(any())).willReturn(new AuthTokenCookies(
                    ResponseCookie.from(CookieManager.ACCESS_TOKEN, "new-access").httpOnly(true).path("/").build(),
                    ResponseCookie.from(CookieManager.REFRESH_TOKEN, "new-refresh").httpOnly(true).path("/").build()));

            MvcTestResult result = mockMvc.post().uri(REISSUE_URI)
                    .cookie(cookie(CookieManager.REFRESH_TOKEN, "whatever"))
                    .exchange();

            assertThat(result).hasStatusOk();

            List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(cookies).hasSize(2);
            assertThat(cookies.get(0)).startsWith("accessToken=new-access");
            assertThat(cookies.get(1)).startsWith("refreshToken=new-refresh");
        }

        @Test
        @DisplayName("만료된 access 쿠키가 붙어 있어도 재발급은 통과한다")
        void expiredAccessCookieDoesNotBlock() {
            given(refreshTokenService.reissue(any())).willReturn(new AuthTokenCookies(
                    ResponseCookie.from(CookieManager.ACCESS_TOKEN, "new-access").build(),
                    ResponseCookie.from(CookieManager.REFRESH_TOKEN, "new-refresh").build()));

            assertThat(mockMvc.post().uri(REISSUE_URI)
                    .cookie(cookie(CookieManager.ACCESS_TOKEN, expiredAccessToken()))
                    .cookie(cookie(CookieManager.REFRESH_TOKEN, "whatever")))
                    .hasStatusOk();
        }
    }

    @Nested
    @DisplayName("DELETE /logout")
    class Logout {

        @Test
        @DisplayName("refresh 쿠키의 기기 세션만 끊는다 — 다른 기기는 그대로다")
        void revokesOnlyThisSession() {
            String refresh = jwtTokenService.createRefreshToken("public-id-42").value();

            MvcTestResult result = mockMvc.delete().uri(LOGOUT_URI)
                    .cookie(cookie(CookieManager.REFRESH_TOKEN, refresh))
                    .exchange();

            assertThat(result).hasStatusOk();
            then(refreshTokenService).should().revokeSession(jwtTokenService.parse(refresh));
            then(refreshTokenService).should(org.mockito.Mockito.never()).revokeAll(org.mockito.ArgumentMatchers.anyString());
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("access 쿠키만 있으면 어느 세션인지 모른다 — 쿠키만 지우고 세션은 건드리지 않는다")
        void accessCookieAloneRevokesNothing() {
            User user = UserFixtures.localUser("user@harucut.com", "encoded", UserStatus.ACTIVE, UserRole.ROLE_USER);

            MvcTestResult result = mockMvc.delete().uri(LOGOUT_URI)
                    .cookie(cookie(CookieManager.ACCESS_TOKEN, accessToken(user)))
                    .exchange();

            assertThat(result).hasStatusOk();
            then(refreshTokenService).shouldHaveNoInteractions();
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("토큰이 하나도 없어도 200이고 Redis를 건드리지 않는다")
        void noTokenStillSucceeds() {
            MvcTestResult result = mockMvc.delete().uri(LOGOUT_URI).exchange();

            assertThat(result).hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"));

            then(refreshTokenService).shouldHaveNoInteractions();
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("쓰레기 refresh 쿠키여도 200이고 Redis를 건드리지 않는다")
        void garbageRefreshCookieStillSucceeds() {
            MvcTestResult result = mockMvc.delete().uri(LOGOUT_URI)
                    .cookie(cookie(CookieManager.REFRESH_TOKEN, "not-a-jwt-at-all"))
                    .exchange();

            assertThat(result).hasStatusOk();
            then(refreshTokenService).shouldHaveNoInteractions();
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("Redis 삭제가 실패해도 200과 만료 쿠키를 준다")
        void survivesRedisFailure() {
            String refresh = jwtTokenService.createRefreshToken("public-id-42").value();
            willThrow(new RedisConnectionFailureException("down"))
                    .given(refreshTokenService).revokeSession(jwtTokenService.parse(refresh));

            MvcTestResult result = mockMvc.delete().uri(LOGOUT_URI)
                    .cookie(cookie(CookieManager.REFRESH_TOKEN, refresh))
                    .exchange();

            assertThat(result).hasStatusOk();
            assertExpiredCookies(result);
        }
    }

    @Nested
    @DisplayName("DELETE /logout/all")
    class LogoutAll {

        private static final String URI = LOGOUT_URI + "/all";

        @Test
        @DisplayName("access 쿠키의 사용자 — 모든 기기 세션을 끊는다")
        void fromPrincipal() {
            User user = UserFixtures.localUser("user@harucut.com", "encoded", UserStatus.ACTIVE, UserRole.ROLE_USER);

            MvcTestResult result = mockMvc.delete().uri(URI).cookie(cookie(CookieManager.ACCESS_TOKEN, accessToken(user))).exchange();

            assertThat(result).hasStatusOk();
            then(refreshTokenService).should().revokeAll(user.getPublicId());
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("access 가 만료됐으면 refresh 쿠키의 사용자")
        void fromRefreshCookie() {
            String refresh = jwtTokenService.createRefreshToken("public-id-42").value();

            assertThat(mockMvc.delete().uri(URI).cookie(cookie(CookieManager.REFRESH_TOKEN, refresh))).hasStatusOk();

            then(refreshTokenService).should().revokeAll("public-id-42");
        }

        @Test
        @DisplayName("토큰이 없어도 200 이고 아무것도 지우지 않는다")
        void noToken() {
            MvcTestResult result = mockMvc.delete().uri(URI).exchange();

            assertThat(result).hasStatusOk();
            then(refreshTokenService).shouldHaveNoInteractions();
            assertExpiredCookies(result);
        }

        @Test
        @DisplayName("Redis 삭제가 실패해도 200 과 만료 쿠키를 준다")
        void survivesRedisFailure() {
            String refresh = jwtTokenService.createRefreshToken("public-id-42").value();
            willThrow(new RedisConnectionFailureException("down")).given(refreshTokenService).revokeAll("public-id-42");

            MvcTestResult result = mockMvc.delete().uri(URI).cookie(cookie(CookieManager.REFRESH_TOKEN, refresh)).exchange();

            assertThat(result).hasStatusOk();
            assertExpiredCookies(result);
        }
    }

    private void assertExpiredCookies(MvcTestResult result) {
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(c -> assertThat(c).contains("Max-Age=0", "HttpOnly", "Path=/"));
        assertThat(cookies.get(0)).startsWith("accessToken=;");
        assertThat(cookies.get(1)).startsWith("refreshToken=;");
    }

    private String accessToken(User user) {
        return jwtTokenService
                .createAccessToken(user.getPublicId(), user.getUserRole(), user.getUserStatus())
                .value();
    }

    private String expiredAccessToken() {
        Clock past = Clock.offset(clock,
                jwtProperties.accessExpiration().plus(Duration.ofMinutes(1)).negated());
        return new JwtTokenService(jwtProperties, past)
                .createAccessToken("expired-user", UserRole.ROLE_USER, UserStatus.ACTIVE).value();
    }

    private Cookie cookie(String name, String value) {
        return new Cookie(name, value);
    }
}
