package com.harucut.payment.controller;

import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.SecurityConfig;
import com.harucut.payment.webhook.PaymentWebhookVerifier;
import com.harucut.payment.webhook.WebhookService;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(PaymentWebhookController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("PaymentWebhookController")
class PaymentWebhookControllerTest extends SecurityBeansMockSupport {

    private static final String WEBHOOK_URI = "/api/payments/webhook";
    private static final String RAW_BODY =
            "{\n  \"eventType\": \"PAYMENT_CANCELED\",\n  \"pgTransactionId\": \"mock-tx-1\"\n}";

    @Autowired
    private MockMvcTester mockMvc;

    @MockitoBean
    private PaymentWebhookVerifier webhookVerifier;

    @MockitoBean
    private WebhookService webhookService;

    @Test
    @DisplayName("서명이 유효하면 토큰 없이도 200이다 — public path")
    void verifiedWebhookReturns200WithoutToken() {
        given(webhookVerifier.verify(any(), any())).willReturn(true);

        assertThat(post(RAW_BODY, "sig-1"))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.data");
    }

    @Test
    @DisplayName("서명 검증에 실패하면 400 PAY-008이고 핸들러가 호출되지 않는다")
    void invalidSignatureIs400() {
        given(webhookVerifier.verify(any(), any())).willReturn(false);

        assertThat(post(RAW_BODY, "bad-sig"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("PAY-008"));

        then(webhookService).shouldHaveNoInteractions();
    }

    /*
     * 서명은 원문 바이트 대상이다. 공백·줄바꿈 하나라도 바뀌면 검증이 깨지므로,
     * 받은 문자열이 한 글자도 안 바뀌고 검증기와 핸들러에 도달해야 한다.
     */
    @Test
    @DisplayName("본문이 파싱 없이 그대로 검증기와 핸들러에 전달된다")
    void rawBodyIsPassedVerbatim() {
        given(webhookVerifier.verify(any(), any())).willReturn(true);
        given(webhookVerifier.provider()).willReturn("portone");

        post(RAW_BODY, "sig-1");

        // 헤더는 소문자 이름으로 검증기에 간다 — Standard Webhooks 의 webhook-id 가 수신 기록 키다
        then(webhookVerifier).should().verify(eq(RAW_BODY), argThat(headers -> "v1,sig-1".equals(headers.get("webhook-signature"))
                && "msg_1".equals(headers.get("webhook-id"))));
        then(webhookService).should().handle("portone", "msg_1", RAW_BODY);
    }

    @Test
    @DisplayName("서명 헤더가 없어도 요청이 거부되지 않고 검증기가 판정한다")
    void missingSignatureHeaderStillReachesVerifier() {
        given(webhookVerifier.verify(any(), any())).willReturn(true);

        assertThat(mockMvc.post().uri(WEBHOOK_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(RAW_BODY))
                .hasStatusOk();

        then(webhookVerifier).should().verify(eq(RAW_BODY), argThat(headers -> !headers.containsKey("webhook-signature")));
    }

    private MvcTestResult post(String body, String signature) {
        return mockMvc.post().uri(WEBHOOK_URI)
                .header("webhook-id", "msg_1")
                .header("Webhook-Signature", "v1," + signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
