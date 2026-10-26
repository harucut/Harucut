package com.harucut.event.service;

import com.harucut.event.entity.Event;
import org.springframework.stereotype.Component;

/**
 * 행사에 적용되는 이용권 기능(WHITE_LABEL·CUSTOM_THEME·BULK_DOWNLOAD) 판정의 한 곳.
 * 계약·이용권(P2B-5) 전에는 어떤 기능도 없다 — 그래서 하루컷 표시 숨김은 아직 게시할 수 없다.
 */
@Component
public class EventFeatures {

    public static final String WHITE_LABEL = "WHITE_LABEL";

    public boolean allows(Event event, String feature) {
        return false;
    }
}
