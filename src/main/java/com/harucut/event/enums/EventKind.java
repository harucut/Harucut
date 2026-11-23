package com.harucut.event.enums;

// 행사 종류(ADR-0009 §4) — 상시 캠페인은 종료 시각을 두지 않고, 운영자가 촬영을 끝내야 보관 종료 대상이 된다
public enum EventKind {
    SCHEDULED,
    ALWAYS_ON
}
