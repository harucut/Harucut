package com.harucut.event.enums;

// 촬영 가능 여부는 상태만으로 정하지 않는다 — LIVE 이면서 일시중지가 아니고 촬영 기간 안이어야 한다(Event.captureOpen)
public enum EventStatus {
    DRAFT, LIVE, CAPTURE_CLOSED, ARCHIVED
}
