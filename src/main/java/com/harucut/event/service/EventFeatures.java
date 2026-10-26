package com.harucut.event.service;

import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 행사에 적용되는 이용권 기능(WHITE_LABEL·CUSTOM_THEME·BULK_DOWNLOAD) 판정의 한 곳 — 적용 이용권(ADR-0008 §6-2)의 기능 목록.
 */
@Component
@RequiredArgsConstructor
public class EventFeatures {

    private final EntitlementService entitlementService;

    public boolean allows(Event event, Feature feature) {
        return entitlementService.allows(event, feature);
    }
}
