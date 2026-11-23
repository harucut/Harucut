package com.harucut.subscription.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.config.SecurityConfig;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.service.SubscriptionService;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

@WebMvcTest(SubscriptionController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("SubscriptionController")
class SubscriptionControllerTest extends SecurityBeansMockSupport {

    private static final String BASE_URI = "/api/auth/subscriptions";
    private static final String CANCEL_URI = "/api/auth/subscriptions/cancel";
    private static final String PUBLIC_ID = "AbCdEf12Gh";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private SubscriptionService subscriptionService;

    @Nested
    @DisplayName("GET /api/auth/subscriptions")
    class GetMySubscription {

        @Test
        @DisplayName("유료 구독은 tier·상태·주기·자동갱신이 그대로 직렬화된다")
        void paidSubscription() {
            given(subscriptionService.getMySubscription(PUBLIC_ID)).willReturn(new SubscriptionResponse(
                    PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                    LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0), true,
                    SubscriptionSource.APP_STORE, "4a1d6f0e-2b3c-3d4e-9f00-112233445566"));

            assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.planTier", p -> assertThat(p).isEqualTo("PLUS"))
                    .hasPathSatisfying("$.data.status", s -> assertThat(s).isEqualTo("ACTIVE"))
                    .hasPathSatisfying("$.data.currentPeriodStart",
                            s -> assertThat(s).isEqualTo("2026-08-01T00:00:00"))
                    .hasPathSatisfying("$.data.currentPeriodEnd",
                            e -> assertThat(e).isEqualTo("2026-09-01T00:00:00"))
                    .hasPathSatisfying("$.data.autoRenew", a -> assertThat(a).isEqualTo(true))
                    .hasPathSatisfying("$.data.source", a -> assertThat(a).isEqualTo("APP_STORE"))
                    .hasPathSatisfying("$.data.storeAccountToken", a -> assertThat(a).isEqualTo("4a1d6f0e-2b3c-3d4e-9f00-112233445566"));
        }

        @Test
        @DisplayName("BASIC은 null 주기 필드가 응답에서 통째로 빠진다 — non_null 직렬화")
        void basicOmitsNullPeriods() {
            given(subscriptionService.getMySubscription(PUBLIC_ID)).willReturn(new SubscriptionResponse(
                    PlanTier.BASIC, SubscriptionStatus.ACTIVE, null, null, false, null, "token"));

            assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.planTier", p -> assertThat(p).isEqualTo("BASIC"))
                    .doesNotHavePath("$.data.currentPeriodStart")
                    .doesNotHavePath("$.data.currentPeriodEnd")
                    .doesNotHavePath("$.data.source");
        }

        @Test
        @DisplayName("구독이 없으면 404 SUBS-004다")
        void noSubscription() {
            given(subscriptionService.getMySubscription(PUBLIC_ID))
                    .willThrow(new BusinessException(SubscriptionErrorCode.NO_ACTIVE_SUBSCRIPTION));

            assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-004"));
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.get().uri(BASE_URI))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(subscriptionService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("POST /api/auth/subscriptions/cancel")
    class Cancel {

        @Test
        @DisplayName("정상 해지는 200이고 principal의 publicId로 서비스를 부른다")
        void success() {
            assertThat(mockMvc.post().uri(CANCEL_URI).cookie(accessCookie()))
                    .hasStatusOk();

            then(subscriptionService).should().cancelAutoRenew(PUBLIC_ID);
        }

        @Test
        @DisplayName("이미 해지된 구독이면 409 SUBS-005다")
        void alreadyCanceled() {
            willThrow(new BusinessException(SubscriptionErrorCode.ALREADY_CANCELED))
                    .given(subscriptionService).cancelAutoRenew(PUBLIC_ID);

            assertThat(mockMvc.post().uri(CANCEL_URI).cookie(accessCookie()))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-005"));
        }

        @Test
        @DisplayName("해지할 자동갱신이 없으면 409 SUBS-006이다")
        void nothingToCancel() {
            willThrow(new BusinessException(SubscriptionErrorCode.NO_AUTO_RENEWAL_TO_CANCEL))
                    .given(subscriptionService).cancelAutoRenew(PUBLIC_ID);

            assertThat(mockMvc.post().uri(CANCEL_URI).cookie(accessCookie()))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-006"));
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
        void unauthenticated() {
            assertThat(mockMvc.post().uri(CANCEL_URI))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(subscriptionService).shouldHaveNoInteractions();
        }
    }

    private Cookie accessCookie() {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }
}