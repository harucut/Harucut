package com.harucut.event.enums;

import java.util.EnumSet;
import java.util.Set;

// 행사 사진 권한 (ADR-0008 §2-2). 설정 변경은 여기 없다 — 공간 역할(OWNER·ADMIN)이 맡는다
public enum PhotoPermission {
    VIEW_PHOTOS, DOWNLOAD_PHOTOS, BULK_DOWNLOAD, DELETE_PHOTOS;

    public static Set<PhotoPermission> all() {
        return EnumSet.allOf(PhotoPermission.class);
    }
}
