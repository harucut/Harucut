package com.harucut.subscription.store.config;

import com.harucut.subscription.enums.PlanTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * 스토어 구독 (ADR-0011 §2).
 *
 * @param verifier  mock(기본 — 로컬·테스트) · real(애플 JWS 서명 검증, 구글은 Play Developer API — 실연동 미확인)
 * @param products  스토어 상품 id → 등급. 여기 없는 상품은 SUBS-010
 * @param graceDays 스토어 구독이 기한을 넘기고도 갱신 알림이 없을 때 강등까지 기다리는 날 — 읽기 시점 등급은 기한에서 바로 BASIC
 */
@ConfigurationProperties(prefix = "billing.store")
public record StoreProperties(
        @DefaultValue("mock") String verifier,
        @DefaultValue Mock mock,
        Map<String, PlanTier> products,
        @DefaultValue Apple apple,
        @DefaultValue Google google,
        @DefaultValue("3") int graceDays
) {

    public StoreProperties {
        products = products == null ? Map.of() : Map.copyOf(products);
    }

    public record Mock(@DefaultValue("false") boolean allowedOutsideLocal) {
    }

    /**
     * @param rootCertificates 신뢰 루트 PEM(운영: Apple Root CA - G3). 비어 있으면 실검증이 모두 실패한다
     * @param allowSandbox     샌드박스 거래도 받는가 — 앱 심사·TestFlight 가 샌드박스로 구매한다
     */
    public record Apple(@DefaultValue("com.harucut.app") String bundleId,
                        @DefaultValue("") String rootCertificates,
                        @DefaultValue("true") boolean allowSandbox) {
    }

    /** @param pushToken Pub/Sub 푸시 URL 의 ?token= 값 — 비어 있으면 RTDN 을 받지 않는다 */
    public record Google(@DefaultValue("com.harucut.app") String packageName,
                         @DefaultValue("") String pushToken) {
    }
}
