package com.harucut.subscription.store;

/** ACTIVE 갱신 중 · CANCELED 자동갱신 꺼짐(만료까지 유효) · PAST_DUE 스토어가 결제 재시도 중 · EXPIRED 만료 · REVOKED 환불·취소 */
public enum StorePurchaseStatus {
    ACTIVE, CANCELED, PAST_DUE, EXPIRED, REVOKED
}
