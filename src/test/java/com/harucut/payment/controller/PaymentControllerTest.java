package com.harucut.payment.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.response.PageResponse;
import com.harucut.config.SecurityConfig;
import com.harucut.payment.dto.PaymentHistoryResponse;
import com.harucut.payment.dto.SubscribeRequest;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.payment.service.PaymentHistoryService;
import com.harucut.payment.service.PaymentService;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("PaymentController")
class PaymentControllerTest extends SecurityBeansMockSupport {

    private static final String SUBSCRIBE_URI = "/api/auth/payments/subscribe";
    private static final String HISTORY_URI = "/api/auth/payments";
    private static final String PUBLIC_ID = "AbCdEf12Gh";
    private static final String VALID_BODY = """
            {
              "planTier": "PLUS",
              "authKey": "auth-1",
              "idempotencyKey": "idem-1"
            }
            """;

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private PaymentHistoryService paymentHistoryService;

    @Test
    @DisplayName("정상 결제는 200이고 구독 정보가 직렬화된다")
    void success() {
        given(paymentService.subscribe(eq(PUBLIC_ID), any(SubscribeRequest.class)))
                .willReturn(new SubscriptionResponse(PlanTier.PLUS, SubscriptionStatus.ACTIVE,
                        LocalDateTime.of(2026, 8, 18, 10, 0), LocalDateTime.of(2026, 9, 18, 10, 0), true,
                        SubscriptionSource.WEB, "token"));

        assertThat(post(VALID_BODY))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.planTier", p -> assertThat(p).isEqualTo("PLUS"))
                .hasPathSatisfying("$.data.status", s -> assertThat(s).isEqualTo("ACTIVE"))
                .hasPathSatisfying("$.data.autoRenew", a -> assertThat(a).isEqualTo(true));
    }

    @Test
    @DisplayName("요청 본문이 DTO로 매핑되어 principal의 publicId와 함께 서비스로 간다")
    void mapsRequestBody() {
        given(paymentService.subscribe(eq(PUBLIC_ID), any(SubscribeRequest.class)))
                .willReturn(new SubscriptionResponse(PlanTier.PLUS, SubscriptionStatus.ACTIVE, null, null, true,
                        SubscriptionSource.WEB, "token"));

        post(VALID_BODY);

        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        then(paymentService).should().subscribe(eq(PUBLIC_ID), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new SubscribeRequest(PlanTier.PLUS, "auth-1", "idem-1"));
    }

    @Test
    @DisplayName("idempotencyKey가 없으면 400 GEN-003이고 서비스가 호출되지 않는다")
    void missingIdempotencyKey() {
        String body = """
                {
                  "planTier": "PLUS",
                  "authKey": "auth-1"
                }
                """;

        assertThat(post(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

        then(paymentService).shouldHaveNoInteractions();
    }

    /*
     * 402와 502 구분이 이 API의 계약이다.
     * 402 = 카드를 바꿔라, 502 = 잠시 후 다시 시도하라.
     */
    @Test
    @DisplayName("결제 실패는 HTTP 402 PAY-002로 나간다")
    void paymentFailureIs402() {
        given(paymentService.subscribe(eq(PUBLIC_ID), any(SubscribeRequest.class)))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_FAILED));

        assertThat(post(VALID_BODY))
                .hasStatus(HttpStatus.PAYMENT_REQUIRED)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("PAY-002"));
    }

    @Test
    @DisplayName("빌링키 발급 실패는 HTTP 502 PAY-001로 나간다")
    void issueFailureIs502() {
        given(paymentService.subscribe(eq(PUBLIC_ID), any(SubscribeRequest.class)))
                .willThrow(new BusinessException(PaymentErrorCode.BILLING_KEY_ISSUE_FAILED));

        assertThat(post(VALID_BODY))
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("PAY-001"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 AUTH-010이고 서비스가 호출되지 않는다")
    void unauthenticated() {
        assertThat(mockMvc.post().uri(SUBSCRIBE_URI)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("내역 조회는 200이고 PageResponse 구조로 직렬화된다 — 파라미터 없으면 0/10")
    void historySerializesPage() {
        given(paymentHistoryService.getMyHistory(PUBLIC_ID, 0, 10))
                .willReturn(PageResponse.from(new PageImpl<>(
                        List.of(new PaymentHistoryResponse("order-pub-1", PlanTier.PLUS, 3900,
                                OrderType.INITIAL, OrderStatus.PAID, LocalDateTime.of(2026, 8, 18, 10, 0))),
                        PageRequest.of(0, 10), 1)));

        assertThat(mockMvc.get().uri(HISTORY_URI).cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.content[0].orderId", o -> assertThat(o).isEqualTo("order-pub-1"))
                .hasPathSatisfying("$.data.content[0].status", s -> assertThat(s).isEqualTo("PAID"))
                .hasPathSatisfying("$.data.content[0].amount", a -> assertThat(a).isEqualTo(3900))
                .hasPathSatisfying("$.data.totalElements", t -> assertThat(t).isEqualTo(1));
    }

    @Test
    @DisplayName("page/size 파라미터가 그대로 서비스에 전달된다")
    void historyPassesPagingParams() {
        given(paymentHistoryService.getMyHistory(PUBLIC_ID, 2, 5))
                .willReturn(PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0)));

        mockMvc.get().uri(HISTORY_URI + "?page=2&size=5").cookie(accessCookie()).exchange();

        then(paymentHistoryService).should().getMyHistory(PUBLIC_ID, 2, 5);
    }

    @Test
    @DisplayName("내역 조회도 토큰이 없으면 401이고 서비스가 호출되지 않는다")
    void historyUnauthenticated() {
        assertThat(mockMvc.get().uri(HISTORY_URI))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        then(paymentHistoryService).shouldHaveNoInteractions();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String body) {
        return mockMvc.post().uri(SUBSCRIBE_URI)
                .cookie(accessCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private Cookie accessCookie() {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }
}
