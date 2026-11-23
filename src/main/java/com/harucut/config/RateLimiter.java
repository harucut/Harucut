package com.harucut.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 공개 경로(참가 세션 발급·부스 연결 교환·결과 수령)의 고정 창 빈도 제한 (ADR-0007 §10).
 *
 * <p>키에는 반드시 TTL 이 붙는다(Lua 에서 INCR 과 함께) — Redis 는 64MB noeviction 이라 영구 키가 쌓이면 쓰기가 막힌다.
 * Redis 가 응답하지 않으면 허용한다(fail-open). 빈도 제한은 남용 완화이지 인가가 아니라서, Redis 장애로 행사 참가
 * 전체가 막히는 쪽이 더 나쁘다. 인가 판정은 이 클래스에 기대지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String PREFIX = "rate:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public boolean tryAcquire(String bucket, String subject, int limit, Duration window) {
        try {
            Long count = redisTemplate.execute(rateLimitScript,
                    List.of(PREFIX + bucket + ":" + subject), String.valueOf(window.toMillis()));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("[rate-limit] Redis 실패 — 허용한다: bucket={}", bucket, e);
            return true;
        }
    }
}
