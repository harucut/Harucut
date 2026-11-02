package com.harucut.auth.service;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.dto.LoginRequest;
import com.harucut.auth.dto.LoginResult;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.exception.CustomAuthenticationException;
import com.harucut.auth.jwt.IssuedToken;
import com.harucut.auth.security.CustomUserPrincipal;
import com.harucut.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoginService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final CookieManager cookieManager;
    private final RefreshTokenService refreshTokenService;

    public LoginResult login(LoginRequest request) {
        CustomUserPrincipal principal = authenticate(request);

        IssuedToken accessToken = jwtTokenService.createAccessToken(
                principal.getPublicId(), principal.getUserRole(), principal.getUserStatus());
        IssuedToken refreshToken = jwtTokenService.createRefreshToken(principal.getPublicId());
        refreshTokenService.save(principal.getPublicId(), refreshToken);

        return new LoginResult(
                cookieManager.createTokenCookie(CookieManager.ACCESS_TOKEN, accessToken),
                cookieManager.createTokenCookie(CookieManager.REFRESH_TOKEN, refreshToken),
                principal.getUserStatus()
        );
    }

    private CustomUserPrincipal authenticate(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

            return (CustomUserPrincipal) authentication.getPrincipal();
        } catch (BadCredentialsException e) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        } catch (AuthenticationException e) {
            if (e.getCause() instanceof CustomAuthenticationException cause) {
                throw new BusinessException(cause.getErrorCode());
            }
            throw new BusinessException(AuthErrorCode.AUTHENTICATION_FAILED);
        }
    }
}
