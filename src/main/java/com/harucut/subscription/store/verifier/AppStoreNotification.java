package com.harucut.subscription.store.verifier;

/**
 * App Store Server Notification v2 — 서명을 확인한 뒤의 값.
 *
 * @param notificationId notificationUUID — 같은 알림의 재전송을 한 번만 처리한다
 * @param transaction    data.signedTransactionInfo(없는 알림도 있다 — null)
 * @param autoRenew      data.signedRenewalInfo 의 autoRenewStatus(없으면 null)
 */
public record AppStoreNotification(String notificationId, String type, String subtype, VerifiedPurchase transaction, Boolean autoRenew) {
}
