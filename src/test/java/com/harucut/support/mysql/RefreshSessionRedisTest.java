package com.harucut.support.mysql;

import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.service.RefreshTokenRepository;
import com.harucut.auth.service.RotationResult;
import com.harucut.config.RedisScriptConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기기 세션 Lua 를 실제 Redis 로 — 다기기 동시 사용, 재사용 감지의 범위, 상한, 전체 회수(R5 15절, O15).
 * Docker 가 필요해 mysqlTest 에서 함께 돈다.
 */
@Tag("mysql")
@Testcontainers
class RefreshSessionRedisTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final Duration TTL = Duration.ofDays(14);
    private static final String USER = "user-1";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final AtomicLong millis = new AtomicLong(Instant.parse("2026-11-02T00:00:00Z").toEpochMilli());
    private RefreshTokenRepository repository;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        RedisScriptConfig scripts = new RedisScriptConfig();
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
        repository = new RefreshTokenRepository(redis, scripts.refreshRotateScript(), scripts.refreshCreateScript(),
                scripts.refreshRevokeAllScript(), clock);
    }

    private static IssuedToken token(String value) {
        return new IssuedToken(value, TTL);
    }

    private RotationResult rotate(String session, String presented, String next) {
        return repository.rotate(USER, session, presented, token(next));
    }

    private void login(String session, String value) {
        repository.save(USER, session, token(value));
    }

    @Test
    @DisplayName("휴대폰과 PC 가 각자 회전한다 — 한쪽 로그인·회전이 다른 쪽을 밀어내지 않는다")
    void devicesRotateIndependently() {
        login("phone", "p1");
        login("pc", "c1");

        assertThat(rotate("phone", "p1", "p2")).isEqualTo(new RotationResult.Rotated("p2"));
        assertThat(rotate("pc", "c1", "c2")).isEqualTo(new RotationResult.Rotated("c2"));
        assertThat(rotate("phone", "p2", "p3")).isEqualTo(new RotationResult.Rotated("p3"));
    }

    @Test
    @DisplayName("10초 안의 동시 재발급은 현재 토큰을 받는다(grace)")
    void graceReturnsCurrent() {
        login("phone", "p1");
        rotate("phone", "p1", "p2");

        assertThat(rotate("phone", "p1", "p2-other")).isEqualTo(new RotationResult.Graced("p2"));
        assertThat(rotate("phone", "p2", "p3")).isEqualTo(new RotationResult.Rotated("p3"));
    }

    @Test
    @DisplayName("옛 토큰을 다시 쓰면 그 세션(토큰 가족)만 끊긴다 — 다른 기기는 그대로다")
    void reuseKillsOnlyThatFamily() {
        login("phone", "p1");
        login("pc", "c1");
        rotate("phone", "p1", "p2");
        redis.delete("REFRESH_GRACE:p1");   // grace 가 지난 뒤

        assertThat(rotate("phone", "p1", "stolen")).isInstanceOf(RotationResult.ReuseDetected.class);
        // 가족 전체가 끊겨 새 토큰도 못 쓴다
        assertThat(rotate("phone", "p2", "p3")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(rotate("pc", "c1", "c2")).isEqualTo(new RotationResult.Rotated("c2"));
    }

    @Test
    @DisplayName("한 기기 로그아웃은 그 세션만 — 목록에서도 빠진다")
    void logoutOneDevice() {
        login("phone", "p1");
        login("pc", "c1");

        repository.delete(USER, "phone");

        assertThat(rotate("phone", "p1", "p2")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(rotate("pc", "c1", "c2")).isEqualTo(new RotationResult.Rotated("c2"));
        assertThat(redis.opsForZSet().range("REFRESH_SESSIONS:USER:" + USER, 0, -1)).containsExactly("pc");
    }

    @Test
    @DisplayName("전체 로그아웃은 모든 세션과 목록을 지운다 — 다른 사용자는 그대로다")
    void logoutAll() {
        login("phone", "p1");
        login("pc", "c1");
        repository.save("user-2", "other", token("o1"));

        repository.deleteAll(USER);

        assertThat(rotate("phone", "p1", "p2")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(rotate("pc", "c1", "c2")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(redis.hasKey("REFRESH_SESSIONS:USER:" + USER)).isFalse();
        assertThat(repository.rotate("user-2", "other", "o1", token("o2"))).isEqualTo(new RotationResult.Rotated("o2"));
    }

    @Test
    @DisplayName("사용자당 상한(10)을 넘으면 오래 안 쓴 세션부터 끊는다 — 최근에 회전한 세션은 살아남는다")
    void capEvictsLeastRecentlyUsed() {
        for (int i = 0; i < 10; i++) {
            millis.addAndGet(1000);
            login("s" + i, "t" + i);
        }
        // 가장 오래된 s0 를 방금 썼다 — 이제 가장 오래 안 쓴 세션은 s1 이다
        millis.addAndGet(1000);
        assertThat(rotate("s0", "t0", "t0b")).isEqualTo(new RotationResult.Rotated("t0b"));

        millis.addAndGet(1000);
        login("s10", "t10");

        assertThat(redis.opsForZSet().size("REFRESH_SESSIONS:USER:" + USER)).isEqualTo(10);
        assertThat(rotate("s1", "t1", "x")).isInstanceOf(RotationResult.NoSession.class);
        assertThat(rotate("s0", "t0b", "t0c")).isEqualTo(new RotationResult.Rotated("t0c"));
        assertThat(rotate("s10", "t10", "t10b")).isEqualTo(new RotationResult.Rotated("t10b"));
    }

    @Test
    @DisplayName("TTL 이 지난 세션은 다음 로그인 때 목록에서 빠진다 — 목록이 쌓이지 않고, 키와 목록 모두 TTL 이 있다")
    void expiredSessionsArePrunedAndKeysHaveTtl() {
        login("old", "o1");
        millis.addAndGet(TTL.toMillis() + 1000);

        login("new", "n1");

        assertThat(redis.opsForZSet().range("REFRESH_SESSIONS:USER:" + USER, 0, -1)).containsExactly("new");
        assertThat(redis.getExpire("REFRESH_TOKEN:USER:" + USER + ":new")).isPositive();
        assertThat(redis.getExpire("REFRESH_SESSIONS:USER:" + USER)).isPositive();
    }
}
