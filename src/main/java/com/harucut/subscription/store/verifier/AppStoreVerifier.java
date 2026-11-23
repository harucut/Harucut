package com.harucut.subscription.store.verifier;

/** App Store — StoreKit 2 의 서명된 거래(JWS)와 App Store Server Notifications v2(JWS) */
public interface AppStoreVerifier {

    /** 앱이 보낸 signedTransaction. 서명·번들이 틀리면 {@link StoreVerificationException} */
    VerifiedPurchase verifyTransaction(String signedTransaction);

    /** 알림의 signedPayload */
    AppStoreNotification verifyNotification(String signedPayload);
}
