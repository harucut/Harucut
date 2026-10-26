package com.harucut.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.harucut.auth.oauth2.app.AppLoginRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class CustomOAuth2FailureHandler implements AuthenticationFailureHandler {

    private static final String CALLBACK_PATH = "/oauth2/callback?error=oauth2";

    private final String callbackUrl;
    private final String appCallbackUrl;

    public CustomOAuth2FailureHandler(@Value("${frontend.url}") String frontendUrl,
                                      @Value("${app.oauth2.callback-url}") String appCallbackUrl) {
        this.callbackUrl = frontendUrl + CALLBACK_PATH;
        this.appCallbackUrl = appCallbackUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("[oauth2] 인증 실패", exception);
        // 앱에서 시작한 로그인이면 앱으로 돌려보낸다 — 사용자가 취소한 경우도 여기로 온다
        response.sendRedirect(AppLoginRequest.of(request).isPresent() ? appCallbackUrl + "?error=oauth2" : callbackUrl);
    }
}
