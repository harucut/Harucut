package com.harucut.auth.oauth2.app;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.dto.LoginResult;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 앱 로그인 (ADR-0011 §1) — 시스템 브라우저에서 끝난 소셜 로그인을 WebView 의 쿠키 세션으로 옮긴다.
 * 브라우저 쪽에는 쿠키 대신 일회용 코드를 주고, WebView 가 그 코드와 PKCE verifier 로 교환하면 이메일 로그인과 같은 쿠키를 내린다.
 * PKCE 덕분에 다른 앱이 harucut:// 딥링크를 가로채 코드를 얻어도 교환하지 못한다.
 */
@Service
@RequiredArgsConstructor
public class AppLoginService {

    private final AppLoginCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final CookieManager cookieManager;

    /** 소셜 로그인 성공 직후 — 코드는 인증 수단이 아니다(교환해야 세션이 된다) */
    public String issue(String userPublicId, String codeChallenge) {
        String code = OpaqueTokens.generate();
        codeRepository.save(code, new AppLoginCodeRepository.Grant(userPublicId, codeChallenge));
        return code;
    }

    public LoginResult exchange(String code, String codeVerifier) {
        AppLoginCodeRepository.Grant grant = codeRepository.consume(code)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.APP_LOGIN_CODE_INVALID));
        if (!MessageDigest.isEqual(s256(codeVerifier).getBytes(StandardCharsets.US_ASCII),
                grant.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(AuthErrorCode.APP_LOGIN_CODE_INVALID);
        }
        // 60초 사이에 바뀌었을 수 있는 역할·상태는 지금 값으로 — 이메일 로그인과 같은 토큰·쿠키
        User user = userRepository.findByPublicId(grant.userPublicId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.APP_LOGIN_CODE_INVALID));
        IssuedToken accessToken = jwtTokenService.createAccessToken(user.getPublicId(), user.getUserRole(), user.getUserStatus());
        IssuedToken refreshToken = jwtTokenService.createRefreshToken(user.getPublicId());
        refreshTokenService.save(user.getPublicId(), refreshToken);
        return new LoginResult(
                cookieManager.createTokenCookie(CookieManager.ACCESS_TOKEN, accessToken),
                cookieManager.createTokenCookie(CookieManager.REFRESH_TOKEN, refreshToken),
                user.getUserStatus());
    }

    // RFC 7636 S256: BASE64URL(SHA-256(ASCII(verifier))), 패딩 없음
    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없는 JVM", e);
        }
    }
}
