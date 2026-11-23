package com.harucut.auth.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.password.PasswordChangeService;
import com.harucut.auth.password.PasswordResetService;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.SecurityConfig;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(PasswordController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
class PasswordControllerTest extends SecurityBeansMockSupport {

    private static final String CODE_URI = "/api/harucut/reset/password/code";
    private static final String VERIFY_URI = "/api/harucut/reset/password/verification";
    private static final String RESET_URI = "/api/harucut/reset/password";
    private static final String CHANGE_URI = "/api/harucut/change/password";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private com.harucut.auth.service.AuthRateLimits authRateLimits;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private PasswordChangeService passwordChangeService;

    @Nested
    @DisplayName("POST /reset/password/code")
    class SendResetCode {

        @Test
        @DisplayName("정상 요청은 200 GEN-000이다")
        void success() {
            assertThat(post(CODE_URI, """
                    {"email":"user@harucut.com"}"""))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"));

            then(passwordResetService).should().sendResetCode("user@harucut.com");
        }

        @Test
        @DisplayName("이메일 형식이 아니면 GEN-003이고 서비스가 호출되지 않는다")
        void invalidEmail() {
            assertThat(post(CODE_URI, """
                    {"email":"not-an-email"}"""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("email"));

            then(passwordResetService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("대문자 이메일은 소문자로 정규화해서 서비스에 넘긴다")
        void normalizesEmail() {
            assertThat(post(CODE_URI, """
                    {"email":"  User@Harucut.com  "}"""))
                    .hasStatusOk();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            then(passwordResetService).should().sendResetCode(captor.capture());
            assertThat(captor.getValue()).isEqualTo("user@harucut.com");
        }
    }

    @Nested
    @DisplayName("POST /reset/password/verification")
    class VerifyResetCode {

        @Test
        @DisplayName("검증에 성공하면 resetToken을 data에 담아 준다")
        void returnsResetToken() {
            given(passwordResetService.verifyResetCode(anyString(), anyString()))
                    .willReturn("550e8400-e29b-41d4-a716-446655440000");

            assertThat(post(VERIFY_URI, """
                    {"email":"user@harucut.com","code":"ABC234"}"""))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"))
                    .hasPathSatisfying("$.data.resetToken",
                            t -> assertThat(t).isEqualTo("550e8400-e29b-41d4-a716-446655440000"));
        }

        @Test
        @DisplayName("code가 비어 있으면 GEN-003이다")
        void blankCode() {
            assertThat(post(VERIFY_URI, """
                    {"email":"user@harucut.com","code":""}"""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(passwordResetService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("PATCH /reset/password")
    class ResetPassword {

        @Test
        @DisplayName("정상 요청은 200 GEN-000이다")
        void success() {
            assertThat(patch(RESET_URI, """
                    {"resetToken":"token-1","newPassword":"newpassword123"}"""))
                    .hasStatusOk();

            then(passwordResetService).should().resetPassword("token-1", "newpassword123");
        }

        @Test
        @DisplayName("새 비밀번호가 7자면 GEN-003이다")
        void passwordTooShort() {
            assertThat(patch(RESET_URI, """
                    {"resetToken":"token-1","newPassword":"pass123"}"""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("newPassword"));

            then(passwordResetService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("새 비밀번호가 21자면 GEN-003이다")
        void passwordTooLong() {
            assertThat(patch(RESET_URI, """
                    {"resetToken":"token-1","newPassword":"%s"}""".formatted("a".repeat(21))))
                    .hasStatus(HttpStatus.BAD_REQUEST);

            then(passwordResetService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("resetToken은 공백을 떼지만 비밀번호는 그대로 넘긴다")
        void trimsTokenButNotPassword() {
            assertThat(patch(RESET_URI, """
                    {"resetToken":"  token-1  ","newPassword":" spaced123 "}"""))
                    .hasStatusOk();

            then(passwordResetService).should().resetPassword("token-1", " spaced123 ");
        }
    }

    @Nested
    @DisplayName("PATCH /change/password")
    class ChangePassword {

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이다")
        void requiresAuthentication() {
            assertThat(patch(CHANGE_URI, """
                    {"oldPassword":"oldpassword123","newPassword":"newpassword456"}"""))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(passwordChangeService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("인증되면 principal의 publicId로 서비스를 부른다")
        void usesPrincipalPublicId() {
            User user = UserFixtures.localUser("user@harucut.com", "encoded",
                    UserStatus.ACTIVE, UserRole.ROLE_USER);

            assertThat(mockMvc.patch().uri(CHANGE_URI)
                    .cookie(accessCookie(user.getPublicId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"oldPassword":"oldpassword123","newPassword":"newpassword456"}"""))
                    .hasStatusOk();

            then(passwordChangeService).should()
                    .changePassword(user.getPublicId(), "oldpassword123", "newpassword456");
        }

        @Test
        @DisplayName("oldPassword가 비어 있으면 GEN-003이다")
        void blankOldPassword() {
            User user = UserFixtures.localUser("user@harucut.com", "encoded",
                    UserStatus.ACTIVE, UserRole.ROLE_USER);

            assertThat(mockMvc.patch().uri(CHANGE_URI)
                    .cookie(accessCookie(user.getPublicId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"oldPassword":"","newPassword":"newpassword456"}"""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(passwordChangeService).shouldHaveNoInteractions();
        }
    }

    private Cookie accessCookie(String publicId) {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(publicId, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }

    private MockMvcTester.MockMvcRequestBuilder post(String uri, String json) {
        return mockMvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockMvcTester.MockMvcRequestBuilder patch(String uri, String json) {
        return mockMvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json);
    }
}