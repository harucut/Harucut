package com.harucut.push.entity;

import java.util.Locale;

/** 앱이 보내는 값은 소문자다(ios·android). 요청 검증은 DTO 의 패턴이 먼저 한다. */
public enum DevicePlatform {
    IOS, ANDROID;

    public static DevicePlatform from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
