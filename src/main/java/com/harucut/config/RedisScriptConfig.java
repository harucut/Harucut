package com.harucut.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<List> refreshRotateScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/refresh-rotate.lua")));
        script.setResultType(List.class);

        script.getSha1();

        return script;
    }

    // 고정 창 빈도 제한(RateLimiter) — INCR 와 만료를 원자적으로
    @Bean
    public RedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        script.setResultType(Long.class);
        script.getSha1();
        return script;
    }
}
