package com.harucut.auth.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.ClientIpResolver;
import com.harucut.config.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 로그인 없이 부르는 인증 경로의 빈도 제한. 넘으면 429 GEN-061. Redis 가 응답하지 않으면 허용한다(RateLimiter).
 * 로그인은 BCrypt 라 적은 요청으로도 CPU 가 차고, 메일은 임의 주소를 돌리면 SMTP 일일 한도가 소진돼 가입·재설정이 전부 멈춘다.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimits {

    private static final int LOGIN_PER_IP = 60;
    private static final int LOGIN_PER_ACCOUNT = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    private static final int MAIL_PER_IP = 5;
    private static final Duration MAIL_WINDOW = Duration.ofMinutes(10);

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public void checkLogin(HttpServletRequest request, String email) {
        if (!rateLimiter.tryAcquire("login-ip", clientIpResolver.resolve(request), LOGIN_PER_IP, LOGIN_WINDOW)
                || !rateLimiter.tryAcquire("login-account", email.toLowerCase(Locale.ROOT), LOGIN_PER_ACCOUNT, LOGIN_WINDOW)) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
    }

    public void checkMailRequest(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("mail-ip", clientIpResolver.resolve(request), MAIL_PER_IP, MAIL_WINDOW)) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
