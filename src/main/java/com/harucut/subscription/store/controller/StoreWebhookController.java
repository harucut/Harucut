package com.harucut.subscription.store.controller;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.subscription.store.config.StoreProperties;
import com.harucut.subscription.store.service.StoreSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

@Slf4j
@Tag(name = "결제 웹훅")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/webhooks")
public class StoreWebhookController {

    private final StoreSubscriptionService storeSubscriptionService;
    private final StoreProperties properties;
    private final ObjectMapper objectMapper;

    @Operation(
            summary = "App Store 서버 알림 v2",
            description = """
                    **애플이 호출한다. 프론트가 부를 API 가 아니다.** 본문 `{ "signedPayload": "<JWS>" }` — 서명(x5c → Apple 루트)을 확인하고
                    같은 notificationUUID 는 한 번만 처리한다. 등록되지 않은 구매의 알림은 건너뛴다(앱의 등록·복원이 반영한다).
                    """)
    @ApiErrors("PAY-008: 서명 검증 실패")
    @PostMapping("/app-store")
    public Response<Void> appStore(@RequestBody String rawBody) {
        storeSubscriptionService.appStoreNotification(read(rawBody).path("signedPayload").asString(null));
        return Response.ok();
    }

    @Operation(
            summary = "Google Play 실시간 개발자 알림(RTDN)",
            description = """
                    **Pub/Sub 푸시가 호출한다. 프론트가 부를 API 가 아니다.** 푸시 URL 의 `token` 이 `billing.store.google.push-token` 과 같아야 한다.
                    알림은 「다시 조회하라」는 신호일 뿐이고 상태는 Play Developer API 로 다시 읽는다(실연동 미확인).
                    """)
    @ApiErrors("PAY-008: 푸시 토큰 불일치")
    @PostMapping("/google-play")
    public Response<Void> googlePlay(@RequestParam(required = false) String token, @RequestBody String rawBody) {
        JsonNode data;
        try {
            data = objectMapper.readTree(Base64.getDecoder().decode(read(rawBody).path("message").path("data").asString("")));
        } catch (IllegalArgumentException | JacksonException e) {
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID, "RTDN 본문을 읽을 수 없다");
        }
        if (!data.path("packageName").isMissingNode() && !properties.google().packageName().equals(data.path("packageName").asString())) {
            log.warn("[스토어] 다른 패키지의 RTDN — 무시");
            storeSubscriptionService.playNotification(token, null, null);
            return Response.ok();
        }
        JsonNode subscription = data.path("subscriptionNotification");
        JsonNode voided = data.path("voidedPurchaseNotification");
        String purchaseToken = !subscription.isMissingNode() ? subscription.path("purchaseToken").asString(null)
                : voided.path("purchaseToken").asString(null);
        storeSubscriptionService.playNotification(token, purchaseToken, subscription.path("subscriptionId").asString(null));
        return Response.ok();
    }

    private JsonNode read(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID, "웹훅 본문이 JSON 이 아니다");
        }
    }
}
