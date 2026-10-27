package com.harucut.subscription.store.verifier;

import com.harucut.subscription.store.StoreEnvironment;
import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.StorePurchaseStatus;

import java.time.LocalDateTime;

/**
 * 스토어가 서명·확인해 준 구매 한 건.
 *
 * @param storeKey     애플 originalTransactionId(갱신해도 같다) · 구글 purchaseToken
 * @param accountToken 구매할 때 앱이 실은 계정 표시(애플 appAccountToken · 구글 obfuscatedExternalAccountId). 없으면 null
 * @param revoked      환불·가족 공유 해제 등으로 취소됐다
 * @param billingRetry 스토어가 결제를 재시도 중이다(유예)
 */
public record VerifiedPurchase(
        StorePlatform platform,
        String storeKey,
        String productId,
        LocalDateTime expiresAt,
        boolean autoRenew,
        boolean revoked,
        boolean billingRetry,
        StoreEnvironment environment,
        String accountToken
) {

    public StorePurchaseStatus statusAt(LocalDateTime now) {
        if (revoked) {
            return StorePurchaseStatus.REVOKED;
        }
        if (!now.isBefore(expiresAt)) {
            return StorePurchaseStatus.EXPIRED;
        }
        if (billingRetry) {
            return StorePurchaseStatus.PAST_DUE;
        }
        return autoRenew ? StorePurchaseStatus.ACTIVE : StorePurchaseStatus.CANCELED;
    }

    public VerifiedPurchase withAutoRenew(boolean value) {
        return new VerifiedPurchase(platform, storeKey, productId, expiresAt, value, revoked, billingRetry, environment, accountToken);
    }
}
