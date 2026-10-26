package com.harucut.auth.controller;

import com.harucut.auth.dto.LoginRequest;
import com.harucut.auth.dto.LoginResult;
import com.harucut.auth.dto.RegisterRequest;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.oauth2.app.AppLoginService;
import com.harucut.auth.service.LoginService;
import com.harucut.auth.service.RegisterService;
import com.harucut.config.SecurityConfig;
import com.harucut.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import com.harucut.support.SecurityBeansMockSupport;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class AuthControllerTest extends SecurityBeansMockSupport {

    @Autowired
    private MockMvcTester mockMvc;

    @MockitoBean
    private RegisterService registerService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private AppLoginService appLoginService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Nested
    @DisplayName("POST /api/harucut/register")
    class Register {

        @Test
        @DisplayName("정상 요청은 200 GEN-000을 반환한다")
        void success() {
            assertThat(post(body("user@harucut.com", "하루컷", "password123")))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"));

            then(registerService).should().register(any(RegisterRequest.class));
        }

        @Test
        @DisplayName("비밀번호가 7자면 GEN-003이고 서비스가 호출되지 않는다")
        void passwordTooShort() {
            assertThat(post(body("user@harucut.com", "하루컷", "pass123")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("password"));

            then(registerService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("비밀번호가 21자면 GEN-003이다")
        void passwordTooLong() {
            assertThat(post(body("user@harucut.com", "하루컷", "a".repeat(21))))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(registerService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("비밀번호가 8자면 통과한다")
        void passwordAtMinBoundary() {
            assertThat(post(body("user@harucut.com", "하루컷", "12345678")))
                    .hasStatusOk();
        }

        @Test
        @DisplayName("이메일 형식이 아니면 GEN-003이다")
        void invalidEmail() {
            assertThat(post(body("not-an-email", "하루컷", "password123")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("email"));

            then(registerService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("POST /api/harucut/login")
    class Login {

        @Test
        @DisplayName("로그인 성공 시 HttpOnly 쿠키 2개가 내려간다")
        void issuesTwoHttpOnlyCookies() {
            given(loginService.login(any(LoginRequest.class))).willReturn(loginResult());

            MvcTestResult result = mockMvc.post().uri("/api/harucut/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("user@harucut.com", "password123"))
                    .exchange();

            assertThat(result)
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"))
                    .hasPathSatisfying("$.data.userStatus", s -> assertThat(s).isEqualTo("ACTIVE"));

            List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(cookies).hasSize(2);
            assertThat(cookies).allSatisfy(c -> assertThat(c).contains("HttpOnly", "Path=/"));
            assertThat(cookies.get(0)).startsWith("accessToken=");
            assertThat(cookies.get(1)).startsWith("refreshToken=");
        }

        @Test
        @DisplayName("이메일 형식이 아니면 GEN-003이고 서비스가 호출되지 않는다")
        void invalidEmail() {
            assertThat(mockMvc.post().uri("/api/harucut/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("not-an-email", "password123")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(loginService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("비밀번호가 비어 있으면 GEN-003이다")
        void blankPassword() {
            assertThat(mockMvc.post().uri("/api/harucut/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("user@harucut.com", "")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(loginService).shouldHaveNoInteractions();
        }

        private LoginResult loginResult() {
            return new LoginResult(
                    ResponseCookie.from("accessToken", "access-value").httpOnly(true).path("/").build(),
                    ResponseCookie.from("refreshToken", "refresh-value").httpOnly(true).path("/").build(),
                    UserStatus.ACTIVE
            );
        }

        private String body(String email, String password) {
            return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
        }
    }

    private MockMvcTester.MockMvcRequestBuilder post(String json) {
        return mockMvc.post().uri("/api/harucut/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private String body(String email, String username, String password) {
        return """
                {"email":"%s","username":"%s","password":"%s"}
                """.formatted(email, username, password);
    }
}