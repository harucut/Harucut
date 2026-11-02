package com.harucut.auth.service;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.dto.AuthTokenCookies;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.jwt.JwtClaims;
import com.harucut.auth.jwt.TokenType;
import com.harucut.common.exception.BusinessException;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final CookieManager cookieManager;

    /** 로그인·앱 코드 교환 — 토큰에 실린 세션으로 저장한다. 다른 기기의 세션을 밀어내지 않는다 */
    public void save(String publicId, IssuedToken refreshToken) {
        repository.save(publicId, jwtTokenService.parse(refreshToken.value()).sessionId(), refreshToken);
    }

    public AuthTokenCookies reissue(String refreshToken) {
        JwtClaims claims = jwtTokenService.parse(refreshToken);

        if (claims.type() != TokenType.REFRESH) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        // refresh 토큰에는 기기 세션이 반드시 실린다
        if (claims.sessionId() == null) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        // 회전보다 먼저 — 정지 때 세션을 지워서, 뒤에서 보면 AUTH-042 대신 세션 없음(AUTH-011)이 된다
        User user = userRepository.findByPublicId(claims.publicId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        if (user.getUserStatus() == UserStatus.BLOCKED) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_BLOCKED);
        }
        IssuedToken candidate = jwtTokenService.createRefreshToken(claims.publicId(), claims.sessionId());
        RotationResult result = repository.rotate(claims.publicId(), claims.sessionId(), refreshToken, candidate);

        String rotated = switch (result) {
            case RotationResult.Rotated r -> r.refreshToken();
            case RotationResult.Graced g -> g.refreshToken();
            case RotationResult.NoSession ignored ->
                    throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            case RotationResult.ReuseDetected ignored -> {
                log.warn("[reissue] refresh 재사용 감지. 그 기기 세션만 끊음. publicId={}", claims.publicId());
                throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            }
        };

        IssuedToken newAccess = jwtTokenService.createAccessToken(
                user.getPublicId(), user.getUserRole(), user.getUserStatus());

        return new AuthTokenCookies(
                cookieManager.createTokenCookie(CookieManager.ACCESS_TOKEN, newAccess),
                cookieManager.createTokenCookie(CookieManager.REFRESH_TOKEN,
                        new IssuedToken(rotated, candidate.ttl()))
        );
    }

    /** 이 refresh 토큰의 기기 세션만 끊는다 — 그 기기의 로그아웃 */
    public void revokeSession(JwtClaims refreshClaims) {
        if (refreshClaims.sessionId() != null) {
            repository.delete(refreshClaims.publicId(), refreshClaims.sessionId());
        }
    }

    /** 모든 기기 세션을 끊는다 — 전체 로그아웃·비밀번호 변경·탈퇴 */
    public void revokeAll(String publicId) {
        repository.deleteAll(publicId);
    }
}
