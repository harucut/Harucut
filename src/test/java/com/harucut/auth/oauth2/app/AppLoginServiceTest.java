package com.harucut.auth.oauth2.app;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.cookie.CookieProperties;
import com.harucut.auth.dto.LoginResult;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static com.harucut.auth.oauth2.app.AppLoginFixtures.CHALLENGE;
import static com.harucut.auth.oauth2.app.AppLoginFixtures.VERIFIER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("앱 로그인 — 일회용 코드·PKCE 교환")
class AppLoginServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private AppLoginService service;
    private User user;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        service = new AppLoginService(new AppLoginCodeRepository(redis), userRepository, jwtTokenService, refreshTokenService,
                new CookieManager(new CookieProperties("localhost", false, "Lax")));
        user = UserFixtures.localUser("app@harucut.com", "e", UserStatus.ACTIVE, UserRole.ROLE_USER);
        given(userRepository.findByPublicId(user.getPublicId())).willReturn(Optional.of(user));
        given(jwtTokenService.createAccessToken(user.getPublicId(), UserRole.ROLE_USER, UserStatus.ACTIVE))
                .willReturn(new IssuedToken("access", Duration.ofMinutes(30)));
        given(jwtTokenService.createRefreshToken(user.getPublicId())).willReturn(new IssuedToken("refresh", Duration.ofDays(14)));
    }

    @Test
    @DisplayName("코드는 해시만 키로 60초 저장한다 — 원문 코드는 Redis 에 없다")
    void issueStoresHashOnly() {
        String code = service.issue(user.getPublicId(), CHALLENGE);

        then(values).should().set(startsWith("oauth2:app:code:"), eq(user.getPublicId() + " " + CHALLENGE), eq(Duration.ofSeconds(60)));
        then(values).should(never()).set(eq("oauth2:app:code:" + code), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("코드·맞는 verifier 면 이메일 로그인과 같은 두 쿠키와 상태, 리프레시 토큰 저장")
    void exchange() {
        given(values.getAndDelete(startsWith("oauth2:app:code:"))).willReturn(user.getPublicId() + " " + CHALLENGE);

        LoginResult result = service.exchange("code", VERIFIER);

        assertThat(result.accessTokenCookie().getName()).isEqualTo(CookieManager.ACCESS_TOKEN);
        assertThat(result.accessTokenCookie().getValue()).isEqualTo("access");
        assertThat(result.refreshTokenCookie().getValue()).isEqualTo("refresh");
        assertThat(result.userStatus()).isEqualTo(UserStatus.ACTIVE);
        then(refreshTokenService).should().save(eq(user.getPublicId()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("verifier 가 틀리면 AUTH-041 — 코드는 이미 꺼내 지웠다(다시 시도할 수 없다), 토큰을 만들지 않는다")
    void wrongVerifier() {
        given(values.getAndDelete(startsWith("oauth2:app:code:"))).willReturn(user.getPublicId() + " " + CHALLENGE, (String) null);

        assertThatThrownBy(() -> service.exchange("code", VERIFIER.replace('d', 'e')))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(AuthErrorCode.APP_LOGIN_CODE_INVALID);
        assertThatThrownBy(() -> service.exchange("code", VERIFIER))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(AuthErrorCode.APP_LOGIN_CODE_INVALID);
        then(jwtTokenService).should(never()).createRefreshToken(anyString());
    }

    @Test
    @DisplayName("없거나 만료된 코드도 같은 AUTH-041")
    void unknownCode() {
        assertThatThrownBy(() -> service.exchange("nope", VERIFIER))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(AuthErrorCode.APP_LOGIN_CODE_INVALID);
    }

    @Test
    @DisplayName("S256 은 RFC 7636 — base64url(SHA-256(verifier)), 패딩 없음")
    void s256() {
        assertThat(AppLoginService.s256(VERIFIER)).isEqualTo(CHALLENGE);
    }
}
