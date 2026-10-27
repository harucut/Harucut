package com.harucut.subscription.store.verifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 구글 Play 실검증 자리 — Play Developer API(purchases.subscriptionsv2.get)는 서비스 계정 키가 있어야 부를 수 있어 아직 붙이지 않았다
 * (실연동 미확인, ADR-0011 §2). real 로 켜면 구글 구매는 전부 검증 실패(SUBS-007)로 거절된다 — 조용히 통과시키지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "billing.store.verifier", havingValue = "real")
public class UnavailablePlayStoreVerifier implements PlayStoreVerifier {

    @Override
    public VerifiedPurchase verify(String productId, String purchaseToken) {
        log.error("[스토어] Google Play 검증기가 구성되지 않았다 — 서비스 계정·Play Developer API 연결 필요");
        throw new StoreVerificationException("Google Play 검증기 미구성");
    }
}
