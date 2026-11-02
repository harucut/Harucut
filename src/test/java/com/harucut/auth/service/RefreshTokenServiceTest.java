package com.harucut.auth.service;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.cookie.CookieProperties;
import com.harucut.auth.dto.AuthTokenCookies;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.jwt.JwtClaims;
import com.harucut.auth.jwt.JwtProperties;
import com.harucut.auth.jwt.TokenType;
import com.harucut.common.exception.BusinessException;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String SECRET =
            "test-secret-key-must-be-at-least-256-bits-for-hs256-algorithm-aa";
    private static final String PUBLIC_ID = "test-public-id";
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private static final Clock CLOCK = Clock.fixed(
            FixedClockConfig.FIXED_NOW.atZone(FixedClockConfig.ZONE).toInstant(),
            FixedClockConfig.ZONE);

    @Mock
    private RefreshTokenRepository repository;

    @Mock
    private UserRepository userRepository;

    private JwtTokenService jwtTokenService;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(properties(), CLOCK);
        refreshTokenService = new RefreshTokenService(
                repository,
                userRepository,
                jwtTokenService,
                new CookieManager(new CookieProperties("localhost", false, "Lax")));
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("토큰에 실린 기기 세션으로 저장한다")
        void savesUnderItsSession() {
            IssuedToken token = jwtTokenService.createRefreshToken(PUBLIC_ID);
            String sessionId = jwtTokenService.parse(token.value()).sessionId();

            refreshTokenService.save(PUBLIC_ID, token);

            assertThat(sessionId).isNotBlank();
            then(repository).should().save(PUBLIC_ID, sessionId, token);
        }

        @Test
        @DisplayName("로그인마다 새 세션이다 — 앱 로그인이 웹 세션을 밀어내지 않는다")
        void everyLoginIsNewSession() {
            assertThat(jwtTokenService.parse(jwtTokenService.createRefreshToken(PUBLIC_ID).value()).sessionId())
                    .isNotEqualTo(jwtTokenService.parse(jwtTokenService.createRefreshToken(PUBLIC_ID).value()).sessionId());
        }
    }

    @Nested
    @DisplayName("reissue")
    class Reissue {

        @Test
        @DisplayName("회전에 성공하면 새 토큰을 쿠키로 내려준다")
        void rotates() {
            String presented = oldRefreshToken();
            givenRotation(presented, new RotationResult.Rotated("new-refresh"));
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);

            AuthTokenCookies cookies = refreshTokenService.reissue(presented);

            assertThat(cookies.refreshTokenCookie().getValue()).isEqualTo("new-refresh");
            assertThat(jwtTokenService.parse(cookies.accessTokenCookie().getValue()).type())
                    .isEqualTo(TokenType.ACCESS);
        }

        @Test
        @DisplayName("스크립트가 만들어 둔 후보 토큰을 그대로 넘긴다")
        void passesCandidateToScript() {
            String presented = oldRefreshToken();
            givenRotation(presented, new RotationResult.Rotated("new-refresh"));
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);

            refreshTokenService.reissue(presented);

            String sessionId = jwtTokenService.parse(presented).sessionId();
            ArgumentCaptor<IssuedToken> candidate = ArgumentCaptor.forClass(IssuedToken.class);
            then(repository).should().rotate(eq(PUBLIC_ID), eq(sessionId), eq(presented), candidate.capture());
            // 회전해도 같은 기기 세션이다
            assertThat(jwtTokenService.parse(candidate.getValue().value()).sessionId()).isEqualTo(sessionId);

            assertThat(candidate.getValue().value()).isNotEqualTo(presented);
            assertThat(candidate.getValue().ttl()).isEqualTo(REFRESH_TTL);
            assertThat(jwtTokenService.parse(candidate.getValue().value()).type())
                    .isEqualTo(TokenType.REFRESH);
        }

        @Test
        @DisplayName("grace 창 안의 동시 요청에는 회전된 현재 토큰을 그대로 준다")
        void gracePeriodReturnsCurrentToken() {
            String presented = oldRefreshToken();
            givenRotation(presented, new RotationResult.Graced("current-refresh"));
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);

            AuthTokenCookies cookies = refreshTokenService.reissue(presented);

            assertThat(cookies.refreshTokenCookie().getValue()).isEqualTo("current-refresh");
        }

        @Test
        @DisplayName("grace로 통과해도 access 토큰은 새로 발급한다")
        void gracePeriodStillIssuesAccessToken() {
            String presented = oldRefreshToken();
            givenRotation(presented, new RotationResult.Graced("current-refresh"));
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);

            AuthTokenCookies cookies = refreshTokenService.reissue(presented);

            assertThat(jwtTokenService.parse(cookies.accessTokenCookie().getValue()).type())
                    .isEqualTo(TokenType.ACCESS);
        }

        /*
         * 이 테스트가 "차단이 최대 30분 안에 반영된다"의 유일한 증거다.
         * 재발급이 DB를 다시 읽어 최신 status를 새 토큰에 싣는지를 본다.
         */
        @Test
        @DisplayName("재발급된 access 토큰에는 DB의 최신 role/status가 실린다")
        void putsFreshAuthorizationClaimsOnNewAccessToken() {
            String presented = oldRefreshToken();
            givenRotation(presented, new RotationResult.Rotated("new-refresh"));
            givenStoredUser(UserStatus.DELETED_REQUESTED, UserRole.ROLE_ADMIN);

            AuthTokenCookies cookies = refreshTokenService.reissue(presented);

            JwtClaims claims = jwtTokenService.parse(cookies.accessTokenCookie().getValue());
            assertThat(claims.status()).isEqualTo(UserStatus.DELETED_REQUESTED);
            assertThat(claims.role()).isEqualTo(UserRole.ROLE_ADMIN);
        }

        @Test
        @DisplayName("사용자가 없으면 회전하지 않고 AUTH-011이다")
        void rejectsWhenUserGone() {
            String presented = oldRefreshToken();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.reissue(presented))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_TOKEN);
            then(repository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("정지 계정은 회전하지 않고 AUTH-042 — 정지 때 세션이 지워졌어도 세션 없음(AUTH-011)으로 뭉개지지 않는다")
        void blockedAccount() {
            String presented = oldRefreshToken();
            givenStoredUser(UserStatus.BLOCKED, UserRole.ROLE_USER);

            assertThatThrownBy(() -> refreshTokenService.reissue(presented))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.ACCOUNT_BLOCKED);
            then(repository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("세션이 없으면 AUTH-011을 던진다")
        void noSession() {
            String presented = oldRefreshToken();
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);
            givenRotation(presented, new RotationResult.NoSession());

            assertThatThrownBy(() -> refreshTokenService.reissue(presented))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("재사용으로 판정되면 AUTH-011을 던진다")
        void reuseDetected() {
            String presented = oldRefreshToken();
            givenStoredUser(UserStatus.ACTIVE, UserRole.ROLE_USER);
            givenRotation(presented, new RotationResult.ReuseDetected());

            assertThatThrownBy(() -> refreshTokenService.reissue(presented))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("access 토큰을 넣으면 저장소를 보지도 않고 AUTH-011을 던진다")
        void accessTokenRejected() {
            String accessToken = jwtTokenService
                    .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value();

            assertThatThrownBy(() -> refreshTokenService.reissue(accessToken))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_TOKEN);

            then(repository).shouldHaveNoInteractions();
            then(userRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("한 기기 로그아웃은 그 세션만 지운다")
        void revokesOneSession() {
            String token = jwtTokenService.createRefreshToken(PUBLIC_ID).value();
            String sessionId = jwtTokenService.parse(token).sessionId();

            refreshTokenService.revokeSession(jwtTokenService.parse(token));

            then(repository).should().delete(PUBLIC_ID, sessionId);
            then(repository).should(never()).deleteAll(anyString());
        }

        @Test
        @DisplayName("전체 회수는 저장소의 전체 삭제로 간다")
        void revokesAll() {
            refreshTokenService.revokeAll(PUBLIC_ID);

            then(repository).should().deleteAll(PUBLIC_ID);
        }

        @Test
        @DisplayName("삭제가 실패하면 예외를 그대로 던진다")
        void propagatesRedisFailure() {
            willThrow(new RedisConnectionFailureException("down"))
                    .given(repository).deleteAll(PUBLIC_ID);

            assertThatThrownBy(() -> refreshTokenService.revokeAll(PUBLIC_ID))
                    .isInstanceOf(RedisConnectionFailureException.class);
        }
    }

    @Test
    @DisplayName("기기 세션이 없는 refresh 토큰은 AUTH-011 — 저장소를 보지 않는다")
    void tokenWithoutSessionRejected() {
        Instant now = CLOCK.instant();
        String noSession = Jwts.builder()
                .subject(PUBLIC_ID)
                .issuer("Harucut")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(REFRESH_TTL)))
                .claim("type", TokenType.REFRESH.name())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> refreshTokenService.reissue(noSession))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);
        then(repository).shouldHaveNoInteractions();
    }

    private void givenRotation(String presented, RotationResult result) {
        given(repository.rotate(eq(PUBLIC_ID), eq(jwtTokenService.parse(presented).sessionId()), eq(presented), any(IssuedToken.class)))
                .willReturn(result);
    }

    /** 정지 여부를 보려고 회전 전에 사용자를 읽는다. */
    private User givenStoredUser(UserStatus status, UserRole role) {
        User user = UserFixtures.localUser("user@harucut.com", "encoded", status, role);
        ReflectionTestUtils.setField(user, "publicId", PUBLIC_ID);
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
        return user;
    }

    private String oldRefreshToken() {
        return new JwtTokenService(properties(), Clock.offset(CLOCK, Duration.ofMinutes(-1)))
                .createRefreshToken(PUBLIC_ID).value();
    }

    private JwtProperties properties() {
        return new JwtProperties(SECRET, Duration.ofMinutes(30), REFRESH_TTL);
    }
}
