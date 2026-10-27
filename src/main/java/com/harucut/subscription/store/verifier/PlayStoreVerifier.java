package com.harucut.subscription.store.verifier;

/** Google Play — 구매 토큰을 Play Developer API 로 조회한다(토큰 자체에는 서명이 없다) */
public interface PlayStoreVerifier {

    VerifiedPurchase verify(String productId, String purchaseToken);
}
