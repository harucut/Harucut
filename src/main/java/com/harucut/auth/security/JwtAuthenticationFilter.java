package com.harucut.auth.security;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.exception.CustomAuthenticationException;
import com.harucut.auth.jwt.JwtClaims;
import com.harucut.auth.jwt.TokenType;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RequestMatcher publicPaths;
    private final JwtTokenService jwtTokenService;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final AccountStateGuard accountStateGuard;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {
            try {
                authenticate(token, request);
            } catch (BusinessException e) {
                reject(request, response, new CustomAuthenticationException(e.getErrorCode()), filterChain);
                return;
            } catch (CustomAuthenticationException e) {
                reject(request, response, e, filterChain);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        JwtClaims claims = jwtTokenService.parse(token);

        if (claims.type() != TokenType.ACCESS) {
            throw new CustomAuthenticationException(AuthErrorCode.INVALID_TOKEN);
        }

        AuthenticatedUser principal = AuthenticatedUser.from(claims);
        // 정지 토큰은 지금 상태로 본다 — 해제하면 access 만료를 기다리지 않고 풀리고, 탈퇴 요청하면 복구로 갈 수 있다. 정지 계정만 조회한다
        if (principal.status() == UserStatus.BLOCKED) {
            principal = accountStateGuard.current(principal.publicId()).orElse(principal);
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, CustomAuthenticationException e, FilterChain chain) throws IOException, ServletException {
        SecurityContextHolder.clearContext();

        if(publicPaths.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        authenticationEntryPoint.commence(request, response, e);
    }

    private String resolveToken(HttpServletRequest request) {
        String fromCookie = resolveFromCookie(request);
        return fromCookie != null ? fromCookie : resolveFromHeader(request);
    }

    private String resolveFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(c -> CookieManager.ACCESS_TOKEN.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String resolveFromHeader(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = header.substring(BEARER_PREFIX.length());
        return StringUtils.hasText(token) ? token : null;
    }
}
