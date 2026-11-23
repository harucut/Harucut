package com.harucut.storage.enums;

// 사용자 업로드의 종류 — 종류마다 사용자 폴더(S3Keys.userRoot) 아래 하위 폴더가 다르다
public enum UploadType {
    PROFILE("profile/"),
    FRAME("frames/"),
    FRAME_COMPONENT("components/"),
    // 네컷 합성의 원본 사진 — 임시 자산(합성 성공 시 삭제). 완성본 업로드 타입은 의도적으로 없다:
    // 결과물은 사용자가 아니라 서버(Lambda)가 만들어 올린다 —
    // 사용자가 올릴 수 있게 하면 합성하지 않은 이미지를 결과물로 위장할 수 있다.
    // 결과물(fourcuts/)과 폴더를 나눈다 — 원본은 임시라서 이 폴더만 따로 치운다(ComposeService.validateSourceOwnership)
    FOURCUT_SOURCE("fourcuts/sources/");

    private final String folder;

    UploadType(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
