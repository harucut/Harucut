package com.harucut.terms.web;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.exception.BusinessException;
import com.harucut.config.SecurityPaths;
import com.harucut.terms.exception.TermsErrorCode;
import com.harucut.terms.service.TermsConsentGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 로그인한 사용자가 현재 필수 약관에 동의하기 전까지 보호 API 를 403 TERMS-004 로 막는다.
 *
 * <p>가입 요청에는 동의를 싣지 않는다 — 이메일 가입 뒤 자동 로그인이 없어 동의 API(인증 필요)를 못
 * 부르고, 소셜 가입은 동의 화면 없이 계정이 생긴다. 그래서 "가입 시점"이 아니라 "로그인 뒤 사용 시점"에
 * 강제한다. 프론트는 이 코드를 받으면 재동의 모달을 연다.
 *
 * <p>필터가 아니라 인터셉터인 이유: 인증이 끝난 뒤에만 의미가 있고, 여기서 던진 예외가
 * GlobalExceptionHandler 를 거쳐 다른 오류와 같은 봉투로 나간다.
 */
@RequiredArgsConstructor
public class TermsConsentInterceptor implements HandlerInterceptor {

    /**
     * 동의 없이도 열어 두는 경로 — 재동의와 복구에 필요한 것들.
     * <ul>
     *   <li>상태 확인·계정 대조: 재동의 브리지가 누구에게 무엇을 물을지 정하는 데 쓴다</li>
     *   <li>동의 조회·제출: 막으면 영원히 풀 수 없다</li>
     *   <li>탈퇴·탈퇴 취소: 동의하지 않을 사용자도 나갈 수 있어야 한다</li>
     *   <li>관리자 API: 관리자가 약관을 처음 등록하는 순간 관리자 본인도 미동의가 된다 —
     *       막으면 약관 관리까지 잠긴다</li>
     * </ul>
     * 비로그인 공개 경로(SecurityPaths.PUBLIC)도 검사하지 않는다 — 로그인 쿠키가 붙어 와도 공개는 공개다.
     */
    private static final RequestMatcher EXEMPT = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/auth/status"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/auth/user/info"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/auth/terms/consents/me"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/terms/consents"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/harucut/exit"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/harucut/reactivate"),
            PathPatternRequestMatcher.pathPattern("/api/admin/**"),
            SecurityPaths.publicMatcher());

    private final TermsConsentGuard termsConsentGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod) || EXEMPT.matches(request)) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return true;   // 비로그인 — 인가는 보안 필터 체인의 몫이다
        }

        if (termsConsentGuard.isConsentMissing(user.publicId())) {
            throw new BusinessException(TermsErrorCode.TERMS_CONSENT_REQUIRED);
        }
        return true;
    }
}
