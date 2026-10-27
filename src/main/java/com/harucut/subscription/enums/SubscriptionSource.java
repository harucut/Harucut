package com.harucut.subscription.enums;

/** 유료 등급의 출처 (ADR-0011 §2). 웹(PortOne 정기결제)만 우리 갱신 배치가 청구하고, 스토어 구독은 스토어 알림·재검증으로 바뀐다 */
public enum SubscriptionSource {
    WEB, APP_STORE, PLAY_STORE, COUPON
}
