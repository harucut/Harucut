package com.harucut.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        @DefaultValue Gateway gateway,
        @DefaultValue Mock mock,
        @DefaultValue("3") int graceDays,
        @DefaultValue Webhook webhook,
        @DefaultValue PortOne portone
) {

    /** 웹훅 검증기 선택 — 결제 게이트웨이(gateway.provider)와 따로 바꾼다(게이트웨이를 바꾸면 청구 경로까지 바뀐다) */
    public record Webhook(
            @DefaultValue("mock") String provider,
            // Standard Webhooks 의 허용 시각차
            @DefaultValue("300") long toleranceSeconds
    ) {
    }

    /** PortOne V2 — 키는 설정으로만 받는다. 값이 없으면 portone 검증기가 기동을 막는다 */
    public record PortOne(
            String webhookSecret,
            String apiSecret,
            String storeId,
            @DefaultValue("https://api.portone.io") String baseUrl
    ) {
    }

    public record Gateway(
            @DefaultValue("mock") String provider
    ){
    }

    public record Mock(
            @DefaultValue("false") boolean failCharge,
            @DefaultValue("false") boolean allowedOutsideLocal
    ){
    }
}
