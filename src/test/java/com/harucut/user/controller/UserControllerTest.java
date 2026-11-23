package com.harucut.user.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.SecurityConfig;
import com.harucut.subscription.dto.SubscriptionUsageResponse;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.service.SubscriptionUsageService;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.dto.UserInfoResponse;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("UserController")
class UserControllerTest extends SecurityBeansMockSupport {

    private static final String INFO_URI = "/api/auth/user/info";
    private static final String USERNAME_URI = "/api/auth/user/change/username";
    private static final String USAGE_URI = "/api/auth/user/subscription/usage";

    private static final String PUBLIC_ID = "AbCdEf12Gh";
    private static final String OTHER_PUBLIC_ID = "ZzYyXx98Ww";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private SubscriptionUsageService subscriptionUsageService;

    @Nested
    @DisplayName("GET /api/auth/user/info")
    class GetInfo {

        @Test
        @DisplayName("인증된 사용자에게 200과 자기 정보를 반환한다")
        void returnsUserInfo() {
            given(userService.getUserInfo(PUBLIC_ID)).willReturn(userInfo());

            assertThat(mockMvc.get().uri(INFO_URI).cookie(accessCookie(PUBLIC_ID)))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"))
                    .hasPathSatisfying("$.data.id", id -> assertThat(id).isEqualTo(PUBLIC_ID));
        }

        @Test
        @DisplayName("토큰의 publicId로만 조회한다 — 다른 사용자 것을 요청할 경로가 없다")
        void usesPublicIdFromToken() {
            given(userService.getUserInfo(OTHER_PUBLIC_ID)).willReturn(userInfo());

            mockMvc.get().uri(INFO_URI)
                    .param("publicId", PUBLIC_ID)
                    .cookie(accessCookie(OTHER_PUBLIC_ID))
                    .exchange();

            then(userService).should().getUserInfo(OTHER_PUBLIC_ID);
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.get().uri(INFO_URI))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("탈퇴 요청 상태면 403 GEN-021이고 서비스가 호출되지 않는다")
        void deletedRequested() {
            assertThat(mockMvc.get().uri(INFO_URI)
                    .cookie(accessCookie(PUBLIC_ID, UserStatus.DELETED_REQUESTED)))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-021"));

            then(userService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("PATCH /api/auth/user/change/username")
    class ChangeUsername {

        @Test
        @DisplayName("정상 요청은 200이고 토큰의 publicId로 서비스를 호출한다")
        void success() {
            assertThat(patch(body("하루컷"))).hasStatusOk();

            then(userService).should().changeUsername(PUBLIC_ID, "하루컷");
        }

        @Test
        @DisplayName("앞뒤 공백을 제거한 값이 서비스에 전달된다")
        void trimsSurroundingWhitespace() {
            assertThat(patch(body("  하루컷  "))).hasStatusOk();

            then(userService).should().changeUsername(PUBLIC_ID, "하루컷");
        }

        @Test
        @DisplayName("공백을 포함해 22자여도 제거 후 20자면 통과한다 — 트림이 검증보다 먼저다")
        void trimsBeforeValidation() {
            String padded = " " + "가".repeat(20) + " ";

            assertThat(patch(body(padded))).hasStatusOk();

            then(userService).should().changeUsername(PUBLIC_ID, "가".repeat(20));
        }

        @Test
        @DisplayName("21자면 GEN-003이고 서비스가 호출되지 않는다")
        void tooLong() {
            assertThat(patch(body("가".repeat(21))))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("username"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("빈 문자열이면 GEN-003이고 서비스가 호출되지 않는다")
        void blank() {
            assertThat(patch(body("")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("공백만 보내면 GEN-003이고 서비스가 호출되지 않는다")
        void whitespaceOnly() {
            assertThat(patch(body("   ")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("전각 공백만 보내면 GEN-003이다 — trim()이었다면 통과했을 값이다")
        void ideographicSpaceOnly() {
            assertThat(patch(body("　")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("본문이 없으면 GEN-006이고 서비스가 호출되지 않는다")
        void missingBody() {
            assertThat(mockMvc.patch().uri(USERNAME_URI)
                    .cookie(accessCookie(PUBLIC_ID))
                    .contentType(MediaType.APPLICATION_JSON))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-006"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.patch().uri(USERNAME_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("하루컷")))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(userService).shouldHaveNoInteractions();
        }

        private MockMvcTester.MockMvcRequestBuilder patch(String json) {
            return mockMvc.patch().uri(USERNAME_URI)
                    .cookie(accessCookie(PUBLIC_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json);
        }

        private String body(String username) {
            return """
                    {"username":"%s"}""".formatted(username);
        }
    }

    @Nested
    @DisplayName("PATCH /api/auth/user/change/profile-image")
    class ChangeProfileImage {

        private static final String PROFILE_IMAGE_URI = "/api/auth/user/change/profile-image";
        private static final String MY_KEY = "uploads/users/" + PUBLIC_ID + "/profile/a1b2c3.png";

        @Test
        @DisplayName("정상 요청은 200이고 토큰의 publicId와 s3Key가 서비스에 전달된다")
        void success() {
            assertThat(patch(body(MY_KEY))).hasStatusOk();

            then(userService).should().changeProfileImage(PUBLIC_ID, MY_KEY);
        }

        @Test
        @DisplayName("서비스가 소유권 위반을 던지면 403 GEN-021 봉투로 나간다")
        void foreignKeyForbidden() {
            String foreignKey = "uploads/users/" + OTHER_PUBLIC_ID + "/profile/a.png";
            willThrow(new BusinessException(GlobalErrorCode.FORBIDDEN))
                    .given(userService).changeProfileImage(PUBLIC_ID, foreignKey);

            assertThat(patch(body(foreignKey)))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-021"));
        }

        @Test
        @DisplayName("s3Key가 빈 문자열이면 GEN-003이고 서비스가 호출되지 않는다")
        void blankKey() {
            assertThat(patch(body("")))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("s3Key"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("본문이 없으면 GEN-006이고 서비스가 호출되지 않는다")
        void missingBody() {
            assertThat(mockMvc.patch().uri(PROFILE_IMAGE_URI)
                    .cookie(accessCookie(PUBLIC_ID))
                    .contentType(MediaType.APPLICATION_JSON))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-006"));

            then(userService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.patch().uri(PROFILE_IMAGE_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(MY_KEY)))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(userService).shouldHaveNoInteractions();
        }

        private MockMvcTester.MockMvcRequestBuilder patch(String json) {
            return mockMvc.patch().uri(PROFILE_IMAGE_URI)
                    .cookie(accessCookie(PUBLIC_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json);
        }

        private String body(String s3Key) {
            return """
                    {"s3Key":"%s"}""".formatted(s3Key);
        }
    }

    @Nested
    @DisplayName("GET /api/auth/user/subscription/usage")
    class SubscriptionUsage {

        @Test
        @DisplayName("PLUS 사용량이 계약 필드명 그대로 직렬화된다")
        void plusUsage() {
            given(subscriptionUsageService.getUsage(PUBLIC_ID))
                    .willReturn(new SubscriptionUsageResponse(PlanTier.PLUS, 3, 1, 2, false));

            assertThat(mockMvc.get().uri(USAGE_URI).cookie(accessCookie(PUBLIC_ID)))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.planTier", p -> assertThat(p).isEqualTo("PLUS"))
                    .hasPathSatisfying("$.data.frameRetentionLimit", l -> assertThat(l).isEqualTo(3))
                    .hasPathSatisfying("$.data.frameRetentionUsedCount", u -> assertThat(u).isEqualTo(1))
                    .hasPathSatisfying("$.data.frameRetentionRemainingCount", r -> assertThat(r).isEqualTo(2))
                    .hasPathSatisfying("$.data.frameRetentionUnlimited", u -> assertThat(u).isEqualTo(false));
        }

        @Test
        @DisplayName("PRO는 한도·잔여가 -1로 나간다 — 무제한 wire 규약")
        void proUsage() {
            given(subscriptionUsageService.getUsage(PUBLIC_ID))
                    .willReturn(new SubscriptionUsageResponse(PlanTier.PRO, -1, 10, -1, true));

            assertThat(mockMvc.get().uri(USAGE_URI).cookie(accessCookie(PUBLIC_ID)))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.frameRetentionLimit", l -> assertThat(l).isEqualTo(-1))
                    .hasPathSatisfying("$.data.frameRetentionRemainingCount", r -> assertThat(r).isEqualTo(-1))
                    .hasPathSatisfying("$.data.frameRetentionUnlimited", u -> assertThat(u).isEqualTo(true));
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.get().uri(USAGE_URI))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(subscriptionUsageService).shouldHaveNoInteractions();
        }
    }

    private UserInfoResponse userInfo() {
        return new UserInfoResponse(
                PUBLIC_ID,
                "user@harucut.com",
                "하루컷",
                "resources/defaults/userDefaultImage.png",
                "HARUCUT",
                "BASIC",
                0,
                UserStatus.ACTIVE
        );
    }

    private Cookie accessCookie(String publicId) {
        return accessCookie(publicId, UserStatus.ACTIVE);
    }

    private Cookie accessCookie(String publicId, UserStatus status) {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(publicId, UserRole.ROLE_USER, status).value());
    }
}
