package com.harucut.payment.controller;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.payment.webhook.PaymentWebhookVerifier;
import com.harucut.payment.webhook.WebhookService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Tag(name = "결제 웹훅")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentWebhookController {

    private final PaymentWebhookVerifier webhookVerifier;
    private final WebhookService webhookService;

    @Operation(
            summary = "결제 웹훅",
            description = """
                    **PG 사가 호출한다. 프론트가 부를 API 가 아니다.**

                    본문을 **파싱하지 않고 원문 문자열 그대로** 받는다. 서명은 원문 바이트를 대상으로
                    검증해야 하는데, 객체로 바꿨다가 다시 문자열로 만들면 필드 순서나 공백이 달라져
                    서명이 깨지기 때문이다.

                    PortOne V2(`payment.webhook.provider=portone`)면 Standard Webhooks 헤더(`webhook-id`·`webhook-timestamp`·
                    `webhook-signature`)로 검증하고, 같은 webhook-id 는 한 번만 처리한다. 결제 이벤트는 PortOne API 로 다시 조회해
                    계약 증빙과 대조한다(실연동 미확인). 기본(mock)은 검증이 항상 통과한다.
                    """)
    @ApiErrors("PAY-008: 서명 검증 실패")
    @PostMapping("/webhook")
    public Response<Void> webhook(@RequestHeader HttpHeaders headers, @RequestBody String rawBody) {
        Map<String, String> lowered = new HashMap<>();
        headers.forEach((name, values) -> lowered.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? null : values.getFirst()));
        if (!webhookVerifier.verify(rawBody, lowered)) {
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        webhookService.handle(webhookVerifier.provider(), lowered.get("webhook-id"), rawBody);
        return Response.ok();
    }
}
