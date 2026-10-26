package com.harucut.auth.security;

import com.harucut.auth.controller.AuthStatusController;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.SecurityConfig;
import com.harucut.notice.controller.NoticeAdminController;
import com.harucut.notice.controller.NoticeController;
import com.harucut.notice.service.NoticeAdminService;
import com.harucut.notice.service.NoticeService;
import com.harucut.subscription.service.SubscriptionUsageService;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.support.UserFixtures;
import com.harucut.terms.controller.TermsAgreementController;
import com.harucut.terms.service.TermsService;
import com.harucut.user.controller.UserController;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * 동의가 빠진 사용자(guard 가 true)를 기준으로, 막히는 경로와 열린 경로를 실제 필터 체인 + 인터셉터로 본다.
 * 경로 판정이 인터셉터에 있으므로 컨트롤러는 실제 것을 쓴다 — 픽스처로 같은 경로를 흉내 내면
 * @SpringBootTest 스캔에서 실제 매핑과 충돌한다.
 */
@WebMvcTest({PlainApiFixtureController.class, AuthStatusController.class, UserController.class,
        TermsAgreementController.class, NoticeController.class, NoticeAdminController.class})
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("약관 동의 강제 (TERMS-004)")
class TermsConsentEnforcementTest extends SecurityBeansMockSupport {

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private SubscriptionUsageService subscriptionUsageService;

    @MockitoBean
    private TermsService termsService;

    @MockitoBean
    private NoticeService noticeService;

    @MockitoBean
    private NoticeAdminService noticeAdminService;

    @BeforeEach
    void consentMissing() {
        given(termsConsentGuard.isConsentMissing(anyString())).willReturn(true);
    }

    @Test
    @DisplayName("동의가 빠졌으면 보호 API 는 403 TERMS-004 다")
    void protectedApiBlocked() {
        assertThat(mockMvc.get().uri("/fixture/plain").cookie(cookie(user(UserRole.ROLE_USER))))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("TERMS-004"));
    }

    @Test
    @DisplayName("동의가 있으면 통과한다")
    void protectedApiPassesWhenAgreed() {
        given(termsConsentGuard.isConsentMissing(anyString())).willReturn(false);

        assertThat(mockMvc.get().uri("/fixture/plain").cookie(cookie(user(UserRole.ROLE_USER))))
                .hasStatusOk();
    }

    @Test
    @DisplayName("상태 확인·계정 대조·동의 조회는 열려 있다 — 재동의 브리지가 쓴다")
    void bridgePathsOpen() {
        Cookie cookie = cookie(user(UserRole.ROLE_USER));

        assertThat(mockMvc.get().uri("/api/auth/status").cookie(cookie)).hasStatusOk();
        assertThat(mockMvc.get().uri("/api/auth/user/info").cookie(cookie)).hasStatusOk();
        assertThat(mockMvc.get().uri("/api/auth/terms/consents/me").cookie(cookie)).hasStatusOk();
    }

    @Test
    @DisplayName("동의 제출은 열려 있다 — 막으면 영원히 풀 수 없다")
    void consentSubmissionOpen() {
        assertThat(mockMvc.post().uri("/api/auth/terms/consents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        [{"code": "tos", "agreed": true}]
                        """)
                .cookie(cookie(user(UserRole.ROLE_USER))))
                .hasStatusOk();
    }

    @Test
    @DisplayName("관리자 API 는 검사하지 않는다 — 약관을 처음 등록한 관리자가 잠기지 않게")
    void adminApiOpen() {
        assertThat(mockMvc.get().uri("/api/admin/notices").cookie(cookie(user(UserRole.ROLE_ADMIN))))
                .hasStatusOk();
        then(termsConsentGuard).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("공개 경로는 로그인 쿠키가 붙어 와도 검사하지 않는다")
    void publicPathOpen() {
        assertThat(mockMvc.get().uri("/api/notices").cookie(cookie(user(UserRole.ROLE_USER))))
                .hasStatusOk();
        then(termsConsentGuard).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("비로그인은 동의 검사 전에 보안 체인이 401 로 끊는다")
    void anonymousGets401() {
        assertThat(mockMvc.get().uri("/fixture/plain"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        then(termsConsentGuard).shouldHaveNoInteractions();
    }

    private User user(UserRole role) {
        return UserFixtures.localUser("consent@harucut.com", "encoded", UserStatus.ACTIVE, role);
    }

    private Cookie cookie(User user) {
        return new Cookie("accessToken", jwtTokenService
                .createAccessToken(user.getPublicId(), user.getUserRole(), user.getUserStatus())
                .value());
    }
}
