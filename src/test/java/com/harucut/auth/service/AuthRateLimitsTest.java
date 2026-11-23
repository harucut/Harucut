package com.harucut.auth.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.config.ClientIpResolver;
import com.harucut.config.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitsTest {

    @Mock
    private RateLimiter rateLimiter;

    private AuthRateLimits limits;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        limits = new AuthRateLimits(rateLimiter, new ClientIpResolver("secret"));
        request = new MockHttpServletRequest();
        request.setRemoteAddr("76.76.21.21");
        request.addHeader(ClientIpResolver.SECRET_HEADER, "secret");
        request.addHeader(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7");
    }

    @Test
    @DisplayName("로그인은 클라이언트 IP(분당 60)와 소문자 이메일(분당 10)을 따로 센다")
    void login() {
        given(rateLimiter.tryAcquire("login-ip", "203.0.113.7", 60, Duration.ofMinutes(1))).willReturn(true);
        given(rateLimiter.tryAcquire("login-account", "a@b.c", 10, Duration.ofMinutes(1))).willReturn(true);

        assertThatCode(() -> limits.checkLogin(request, "A@B.c")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IP 나 이메일 어느 쪽이든 넘으면 429")
    void loginExceeded() {
        given(rateLimiter.tryAcquire("login-ip", "203.0.113.7", 60, Duration.ofMinutes(1))).willReturn(false);
        assertThatThrownBy(() -> limits.checkLogin(request, "a@b.c")).isInstanceOf(BusinessException.class);

        given(rateLimiter.tryAcquire("login-ip", "203.0.113.7", 60, Duration.ofMinutes(1))).willReturn(true);
        given(rateLimiter.tryAcquire("login-account", "a@b.c", 10, Duration.ofMinutes(1))).willReturn(false);
        assertThatThrownBy(() -> limits.checkLogin(request, "a@b.c")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("메일 요청은 IP 당 10분에 5회")
    void mail() {
        given(rateLimiter.tryAcquire("mail-ip", "203.0.113.7", 5, Duration.ofMinutes(10))).willReturn(true, false);

        assertThatCode(() -> limits.checkMailRequest(request)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.checkMailRequest(request)).isInstanceOf(BusinessException.class);
    }
}
