package com.harucut.auth.security;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.SecurityPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 정지되거나 ADMIN 권한이 빠지면 access 토큰이 살아 있어도 관리·사진 경로는 바로 403 GEN-021 이다(SecurityPaths.ACCOUNT_CHECKED).
 *
 * <p>TermsConsentInterceptor 와 같은 이유로 인터셉터다 — 인증이 끝난 뒤에만 의미가 있고, 예외가 다른 오류와 같은 봉투로 나간다.
 */
@RequiredArgsConstructor
public class AccountStateInterceptor implements HandlerInterceptor {

    private static final RequestMatcher CHECKED = SecurityPaths.accountCheckedMatcher();
    private static final RequestMatcher ADMIN = PathPatternRequestMatcher.pathPattern(SecurityPaths.ADMIN);

    private final AccountStateGuard accountStateGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod) || !CHECKED.matches(request)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return true;   // 비로그인 — 인가는 보안 필터 체인의 몫이다
        }
        if (accountStateGuard.isDenied(user.publicId(), ADMIN.matches(request))) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
        return true;
    }
}
