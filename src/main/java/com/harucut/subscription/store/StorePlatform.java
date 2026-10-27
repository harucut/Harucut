package com.harucut.subscription.store;

import com.harucut.subscription.enums.SubscriptionSource;

public enum StorePlatform {
    APP_STORE, PLAY_STORE;

    public SubscriptionSource source() {
        return this == APP_STORE ? SubscriptionSource.APP_STORE : SubscriptionSource.PLAY_STORE;
    }
}
