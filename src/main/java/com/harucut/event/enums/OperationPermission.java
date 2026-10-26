package com.harucut.event.enums;

import java.util.EnumSet;
import java.util.Set;

// 행사 운영 권한(ADR-0009 §3) — 사진 권한(PhotoPermission)과 따로 준다. OWNER·ADMIN 은 셋 다 가진다
public enum OperationPermission {
    // 설정·게시·일시중지·촬영 종료·복제
    MANAGE_EVENT,
    // 부스 기기 등록·재발급·회수
    MANAGE_BOOTH,
    // 행사 고객 초대·사진 권한 관리(자기가 가진 사진 권한 안에서)
    INVITE_CUSTOMERS;

    public static Set<OperationPermission> all() {
        return EnumSet.allOf(OperationPermission.class);
    }
}
