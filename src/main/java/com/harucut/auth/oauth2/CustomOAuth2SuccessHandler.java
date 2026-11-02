package com.harucut.auth.oauth2;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.oauth2.app.AppLoginRequest;
import com.harucut.auth.oauth2.app.AppLoginService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class CustomOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final String CALLBACK_PATH = "/oauth2/callback";

    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final CookieManager cookieManager;
    private final AppLoginService appLoginService;
    private final String callbackUrl;
    private final String appCallbackUrl;

    public CustomOAuth2SuccessHandler(JwtTokenService jwtTokenService,
                                      RefreshTokenService refreshTokenService,
                                      CookieManager cookieManager,
                                      AppLoginService appLoginService,
                                      @Value("${frontend.url}") String frontendUrl,
                                      @Value("${app.oauth2.callback-url}") String appCallbackUrl) {
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.cookieManager = cookieManager;
        this.appLoginService = appLoginService;
        this.callbackUrl = frontendUrl + CALLBACK_PATH;
        this.appCallbackUrl = appCallbackUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        AuthenticatedUser user = ((CustomOAuth2User) authentication.getPrincipal()).authenticatedUser();

        // 앱(시스템 브라우저)에서 시작한 로그인 — 이 브라우저의 쿠키는 WebView 로 넘어가지 않는다. 쿠키 대신 일회용 코드를 앱 스킴으로
        Optional<AppLoginRequest> app = AppLoginRequest.of(request);
        if (app.isPresent()) {
            String challenge = app.get().codeChallenge();
            response.sendRedirect(challenge == null
                    ? appCallbackUrl + "?error=invalid_request"   // PKCE 값이 없거나 모양이 틀렸다 — 교환할 수 없는 코드는 주지 않는다
                    : appCallbackUrl + "?code=" + appLoginService.issue(user.publicId(), challenge));
            return;
        }

        IssuedToken accessToken = jwtTokenService.createAccessToken(user.publicId(), user.role(), user.status());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieManager.createTokenCookie(CookieManager.ACCESS_TOKEN, accessToken).toString());
        // 정지 계정 — 리다이렉트라 AUTH-042 를 본문으로 줄 수 없다. 상태 조회만 되는 access 토큰만 주면
        // 콜백 화면이 BLOCKED 를 읽어 이유와 문의처를 말하고 지운다. 세션(refresh)은 만들지 않는다
        if (user.status() != UserStatus.BLOCKED) {
            IssuedToken refreshToken = jwtTokenService.createRefreshToken(user.publicId());
            refreshTokenService.save(user.publicId(), refreshToken);
            response.addHeader(HttpHeaders.SET_COOKIE,
                    cookieManager.createTokenCookie(CookieManager.REFRESH_TOKEN, refreshToken).toString());
        }

        response.sendRedirect(callbackUrl);
    }
}
