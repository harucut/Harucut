package com.harucut.auth.service;

import com.harucut.auth.jwt.IssuedToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 기기 세션별 refresh 토큰. 세션마다 키가 하나고, 사용자별 목록(ZSET, 점수는 마지막 사용 시각)이 전체 회수와 상한을 맡는다.
 * 쓰는 일은 전부 Lua 로 원자적이다 — 회전 중에 전체 로그아웃이 끼어들어 세션이 되살아나지 않게.
 */
@Repository
public class RefreshTokenRepository {

    private static final String SESSION_PREFIX = "REFRESH_TOKEN:USER:";
    private static final String GRACE_PREFIX = "REFRESH_GRACE:";
    private static final String SESSIONS_PREFIX = "REFRESH_SESSIONS:USER:";

    private static final Duration GRACE_TTL = Duration.ofSeconds(10);
    // 사용자당 기기 세션 — 휴대폰·PC·앱을 합쳐도 넉넉하다. 넘으면 오래 안 쓴 세션부터 끊는다
    static final int MAX_SESSIONS_PER_USER = 10;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> refreshRotateScript;
    private final RedisScript<Long> refreshCreateScript;
    private final RedisScript<Long> refreshRevokeAllScript;
    private final Clock clock;

    public RefreshTokenRepository(StringRedisTemplate redisTemplate, RedisScript<List> refreshRotateScript,
                                  RedisScript<Long> refreshCreateScript, RedisScript<Long> refreshRevokeAllScript, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.refreshRotateScript = refreshRotateScript;
        this.refreshCreateScript = refreshCreateScript;
        this.refreshRevokeAllScript = refreshRevokeAllScript;
        this.clock = clock;
    }

    public void save(String publicId, String sessionId, IssuedToken refreshToken) {
        redisTemplate.execute(refreshCreateScript,
                List.of(sessionKey(publicId, sessionId), sessionsKey(publicId)),
                refreshToken.value(),
                String.valueOf(refreshToken.ttl().toSeconds()),
                sessionId,
                now(),
                String.valueOf(MAX_SESSIONS_PER_USER),
                sessionPrefix(publicId));
    }

    /** 기기 세션 하나만 끊는다 — 다른 기기는 그대로다 */
    public void delete(String publicId, String sessionId) {
        redisTemplate.delete(sessionKey(publicId, sessionId));
        redisTemplate.opsForZSet().remove(sessionsKey(publicId), sessionId);
    }

    public void deleteAll(String publicId) {
        redisTemplate.execute(refreshRevokeAllScript,
                List.of(sessionsKey(publicId)),
                sessionPrefix(publicId));
    }

    @SuppressWarnings("unchecked")
    public RotationResult rotate(String publicId, String sessionId, String presented, IssuedToken candidate) {
        List<String> result = redisTemplate.execute(
                refreshRotateScript,
                List.of(sessionKey(publicId, sessionId), GRACE_PREFIX + presented, sessionsKey(publicId)),
                presented,
                candidate.value(),
                String.valueOf(candidate.ttl().toSeconds()),
                String.valueOf(GRACE_TTL.toSeconds()),
                sessionId,
                now());

        return toRotationResult(result);
    }

    private RotationResult toRotationResult(List<String> result) {
        return switch (result.get(0)) {
            case "ROTATED" -> new RotationResult.Rotated(result.get(1));
            case "GRACE" -> new RotationResult.Graced(result.get(1));
            case "NO_SESSION" -> new RotationResult.NoSession();
            case "REUSE" -> new RotationResult.ReuseDetected();
            default -> throw new IllegalStateException("알 수 없는 회전 결과: " + result.get(0));
        };
    }

    private String now() {
        return String.valueOf(clock.millis());
    }

    private static String sessionPrefix(String publicId) {
        return SESSION_PREFIX + publicId + ":";
    }

    private static String sessionKey(String publicId, String sessionId) {
        return sessionPrefix(publicId) + sessionId;
    }

    private static String sessionsKey(String publicId) {
        return SESSIONS_PREFIX + publicId;
    }
}
