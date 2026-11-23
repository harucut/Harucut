package com.harucut.subscription.store.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 목 검증기는 서명을 보지 않는다 — 로컬·테스트 밖에서는 명시적으로 허용하지 않으면 기동을 막는다(MockGatewayGuard 와 같은 선택) */
@Slf4j
@Component
@Profile("!local & !test")
public class StoreMockGuard {

    public StoreMockGuard(StoreProperties properties) {
        if (!"mock".equals(properties.verifier())) {
            return;
        }
        if (!properties.mock().allowedOutsideLocal()) {
            throw new IllegalStateException("Mock store verifier must not be active outside local/test profiles.");
        }
        log.warn("[스토어] 목 검증기가 허용된 채로 기동했다 — 스토어 구매 서명을 확인하지 않는다. "
                + "검증기를 붙이면 billing.store.mock.allowed-outside-local(STORE_MOCK_ALLOWED)을 지울 것");
    }
}
