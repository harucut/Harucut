package com.harucut.auth.service;

import com.harucut.auth.jwt.IssuedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/** 결과 문자열 → 타입 매핑만 본다. Lua 의 실제 동작은 실제 Redis 로 시험한다(RefreshSessionRedisTest) */
@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryTest {

    private static final IssuedToken CANDIDATE = new IssuedToken("candidate", Duration.ofDays(14));

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<List> rotateScript;

    @Mock
    private RedisScript<Long> createScript;

    @Mock
    private RedisScript<Long> revokeAllScript;

    private RefreshTokenRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RefreshTokenRepository(redisTemplate, rotateScript, createScript, revokeAllScript,
                Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
    }

    private RotationResult rotateReturning(String status, String token) {
        given(redisTemplate.execute(eq(rotateScript), anyList(), any(Object[].class)))
                .willReturn(List.of(status, token));
        return repository.rotate("p", "s", "presented", CANDIDATE);
    }

    @Test
    @DisplayName("ROTATED 면 스크립트가 저장한 새 토큰, GRACE 면 현재 토큰을 준다")
    void tokens() {
        assertThat(rotateReturning("ROTATED", "new")).isEqualTo(new RotationResult.Rotated("new"));
        assertThat(rotateReturning("GRACE", "current")).isEqualTo(new RotationResult.Graced("current"));
    }

    @Test
    @DisplayName("NO_SESSION·REUSE 면 토큰 없는 결과를 준다")
    void noToken() {
        assertThat(rotateReturning("NO_SESSION", "")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(rotateReturning("REUSE", "")).isInstanceOf(RotationResult.ReuseDetected.class);
    }

    @Test
    @DisplayName("모르는 상태 문자열이면 조용히 통과시키지 않는다")
    void unknownStatus() {
        assertThatThrownBy(() -> rotateReturning("WAT", "")).isInstanceOf(IllegalStateException.class);
    }
}
