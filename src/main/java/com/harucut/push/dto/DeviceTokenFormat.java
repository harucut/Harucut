package com.harucut.push.dto;

public final class DeviceTokenFormat {

    /** Expo 푸시 토큰 — ExponentPushToken[…] 또는 ExpoPushToken[…]. */
    public static final String EXPO = "^Expo(nent)?PushToken\\[[A-Za-z0-9_\\-]{1,200}]$";

    private DeviceTokenFormat() {
    }
}
