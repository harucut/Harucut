package com.harucut.asset.enums;

import com.harucut.storage.enums.ContentType;

import java.util.Set;

// 자산의 용도. 받는 형식과 받는 경로가 용도마다 다르다 (ADR-0006 §2)
public enum AssetPurpose {

    // 배경·PHOTO·STICKER·로고. 합성기(ImageIO)가 읽어야 하므로 PNG·JPEG 만
    FRAME_ASSET(Set.of(ContentType.PNG, ContentType.JPEG)),
    // 제작자 브라우저가 구운 글자 PNG
    TEXT_RENDER(Set.of(ContentType.PNG)),
    // 행사 페이지 테마(P2B)
    PAGE_THEME_ASSET(Set.of(ContentType.PNG, ContentType.JPEG)),
    PROFILE(Set.of(ContentType.PNG, ContentType.JPEG, ContentType.GIF)),
    // 행사 참가자의 촬영 입력(P2A 참가자 경로 전용)
    CAPTURE_INPUT(Set.of(ContentType.PNG, ContentType.JPEG));

    private final Set<ContentType> allowedTypes;

    AssetPurpose(Set<ContentType> allowedTypes) {
        this.allowedTypes = allowedTypes;
    }

    public boolean accepts(ContentType contentType) {
        return allowedTypes.contains(contentType);
    }
}
