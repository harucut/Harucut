package com.harucut.auth.email;

import com.harucut.config.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class EmailRateLimit {

    private static final String COOLDOWN_PREFIX = "email:cooldown:";
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final Duration DAILY_WINDOW = Duration.ofDays(1);
    private static final int MAX_CODE_MISSES = 5;
    private static final Duration CODE_MISS_WINDOW = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final RateLimiter rateLimiter;
    private final int dailyLimit;

    public EmailRateLimit(StringRedisTemplate redisTemplate, RateLimiter rateLimiter,
                          @Value("${mail.daily-limit:400}") int dailyLimit) {
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
        this.dailyLimit = dailyLimit;
    }

    /** 서비스 전체의 하루 발송 상한을 넘었는가 — SMTP 계정이 정지되기 전에 멈춘다. 호출할 때마다 한 건으로 센다 */
    public boolean dailySendLimitExceeded() {
        return !rateLimiter.tryAcquire("mail-daily", "all", dailyLimit, DAILY_WINDOW);
    }

    /**
     * 인증 코드를 틀린 횟수가 상한을 넘었는가 — 코드는 32^6 가지라 제한이 없으면 대입으로 뚫린다.
     * 넘으면 호출자가 코드를 지운다. 새 코드는 쿨다운을 지나 메일로만 받을 수 있다
     */
    public boolean codeAttemptsExceeded(String email) {
        return !rateLimiter.tryAcquire("email-code-miss", email.toLowerCase(Locale.ROOT), MAX_CODE_MISSES, CODE_MISS_WINDOW);
    }

    public boolean tryAcquireCooldown(String email) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key(COOLDOWN_PREFIX, email), "1", COOLDOWN_TTL);

        return Boolean.TRUE.equals(acquired);
    }

    public void releaseCooldown(String email) {
        redisTemplate.delete(key(COOLDOWN_PREFIX, email));
    }

    private String key(String prefix, String email) {
        return prefix + email.toLowerCase(Locale.ROOT);
    }
}
