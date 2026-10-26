package com.harucut.auth.oauth2.app;

import com.harucut.common.utils.OpaqueTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/** 앱 로그인 일회용 코드 — 해시만 키로 남기고, 값은 「사용자 public id + PKCE 값」. 60초, 꺼내면 지운다 */
@Repository
@RequiredArgsConstructor
public class AppLoginCodeRepository {

    private static final String PREFIX = "oauth2:app:code:";
    static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public record Grant(String userPublicId, String codeChallenge) {
    }

    public void save(String code, Grant grant) {
        redisTemplate.opsForValue().set(key(code), grant.userPublicId() + " " + grant.codeChallenge(), TTL);
    }

    /** 한 번만 — 검증값이 틀려도 코드는 이미 소모됐다(추측 반복을 막는다) */
    public Optional<Grant> consume(String code) {
        String value = redisTemplate.opsForValue().getAndDelete(key(code));
        if (value == null) {
            return Optional.empty();
        }
        int space = value.indexOf(' ');
        return Optional.of(new Grant(value.substring(0, space), value.substring(space + 1)));
    }

    private static String key(String code) {
        return PREFIX + OpaqueTokens.hash(code);
    }
}
