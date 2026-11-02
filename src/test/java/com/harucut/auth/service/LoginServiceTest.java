package com.harucut.auth.service;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.cookie.CookieProperties;
import com.harucut.auth.dto.LoginRequest;
import com.harucut.auth.dto.LoginResult;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.jwt.JwtProperties;
import com.harucut.auth.jwt.TokenType;
import com.harucut.auth.security.CustomUserDetailsService;
import com.harucut.common.exception.BusinessException;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String EMAIL = "user@harucut.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String SECRET =
            "test-secret-key-must-be-at-least-256-bits-for-hs256-algorithm-aa";

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private JwtTokenService jwtTokenService;
    private LoginService loginService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = jwtTokenService();
        loginService = new LoginService(
                authenticationManager(new CustomUserDetailsService(userRepository)),
                jwtTokenService,
                cookieManager(),
                refreshTokenService
        );
    }

    @Nested
    @DisplayName("로그인 성공")
    class Success {

        @Test
        @DisplayName("access/refresh 쿠키 2개와 userStatus를 돌려준다")
        void issuesTwoCookies() {
            User user = givenLocalUser(UserStatus.ACTIVE);

            LoginResult result = loginService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(result.accessTokenCookie().getName()).isEqualTo("accessToken");
            assertThat(result.refreshTokenCookie().getName()).isEqualTo("refreshToken");
            assertThat(result.userStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(jwtTokenService.parse(result.accessTokenCookie().getValue()).publicId())
                    .isEqualTo(user.getPublicId());
        }

        @Test
        @DisplayName("두 쿠키의 타입이 각각 ACCESS, REFRESH다")
        void issuesDistinctTokenTypes() {
            givenLocalUser(UserStatus.ACTIVE);

            LoginResult result = loginService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(jwtTokenService.parse(result.accessTokenCookie().getValue()).type())
                    .isEqualTo(TokenType.ACCESS);
            assertThat(jwtTokenService.parse(result.refreshTokenCookie().getValue()).type())
                    .isEqualTo(TokenType.REFRESH);
        }

        @Test
        @DisplayName("차단된 사용자도 로그인은 성공하고 BLOCKED를 돌려준다")
        void blockedUserCanStillLogIn() {
            givenLocalUser(UserStatus.BLOCKED);

            LoginResult result = loginService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(result.userStatus()).isEqualTo(UserStatus.BLOCKED);
        }

        @Test
        @DisplayName("발급한 refresh를 Redis에 저장하고, 쿠키로 내려준 값과 같다")
        void savesRefreshToken() {
            User user = givenLocalUser(UserStatus.ACTIVE);

            LoginResult result = loginService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            ArgumentCaptor<IssuedToken> saved = ArgumentCaptor.forClass(IssuedToken.class);
            then(refreshTokenService).should().save(eq(user.getPublicId()), saved.capture());

            assertThat(saved.getValue().value()).isEqualTo(result.refreshTokenCookie().getValue());
        }
    }

    @Nested
    @DisplayName("로그인 실패")
    class Failure {

        @Test
        @DisplayName("비밀번호가 틀리면 AUTH-001이다")
        void wrongPassword() {
            givenLocalUser(UserStatus.ACTIVE);

            assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, "wrong-password")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("해당 이메일의 로컬 계정이 없으면 AUTH-020이다")
        void unknownEmail() {
            given(userRepository.findByProviderAndEmail(Provider.HARUCUT, EMAIL))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("UsernameNotFoundException을 던지면 AUTH-020이 아니라 AUTH-001로 감춰진다")
        void usernameNotFoundIsHiddenAsBadCredentials() {
            LoginService service = new LoginService(
                    authenticationManager(email -> {
                        throw new UsernameNotFoundException(email);
                    }),
                    jwtTokenService, cookieManager(),
                    refreshTokenService
            );

            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("그 밖의 인증 실패는 AUTH-010이다")
        void otherAuthenticationFailure() {
            LoginService service = new LoginService(
                    authenticationManager(email -> {
                        throw new LockedException(email);
                    }),
                    jwtTokenService, cookieManager(),
                    refreshTokenService
            );

            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private User givenLocalUser(UserStatus status) {
        User user = UserFixtures.localUser(EMAIL, passwordEncoder.encode(RAW_PASSWORD), status, UserRole.ROLE_USER);
        given(userRepository.findByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(Optional.of(user));
        return user;
    }

    private AuthenticationManager authenticationManager(UserDetailsService userDetailsService) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private JwtTokenService jwtTokenService() {
        return new JwtTokenService(
                new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)),
                Clock.fixed(FixedClockConfig.FIXED_NOW.atZone(FixedClockConfig.ZONE).toInstant(),
                        FixedClockConfig.ZONE));
    }

    private CookieManager cookieManager() {
        return new CookieManager(new CookieProperties("localhost", false, "Lax"));
    }
}