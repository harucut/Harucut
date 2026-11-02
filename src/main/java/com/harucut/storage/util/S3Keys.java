package com.harucut.storage.util;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;

import java.net.URI;

public final class S3Keys {

    private S3Keys() {}

    public static final String UPLOAD_ROOT = "uploads/";

    public static String userRoot(String publicId) {
        return "uploads/users/" + publicId + "/";
    }

    // 내려받을 파일 이름에 붙이는 확장자(점 포함). 없으면 결과물 기본인 .jpg
    public static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? ".jpg" : key.substring(dot);
    }

    // 우리 버킷이 관리하는 key인가 — uploads/ 아래만 우리 소유다
    public static boolean isManagedKey(String key) {
        return key != null && key.startsWith(UPLOAD_ROOT);
    }

    // 소유권 판정의 유일한 자리. 이 규칙이 여러 곳에 복제돼 있었고, 그중 프레임 경로에서만
    // 빠져 있어서 남의 key를 자기 프레임에 심을 수 있었다 (읽기·합성 반출·삭제까지 이어졌다).
    //
    // 계약: "관리 대상(uploads/) key라면 반드시 내 폴더 아래여야 한다."
    // 관리 대상이 아닌 값(정적 스티커 경로, 외부 URL, TEXT 본문)은 통과시킨다 — 우리 파일이 아니라
    // 소유권을 따질 대상이 아니다. 그래서 이 검사를 넣어도 기존 정상 입력은 하나도 막히지 않는다.
    //
    // 경로 조작도 여기서 끊는다. S3는 key를 불투명 문자열로 다뤄 오늘은 ".."가 해석되지 않지만,
    // SDK나 앞단 프록시가 정규화하는 구성으로 바뀌면 조회를 넘어 삭제까지 남의 파일로 간다.
    // "오늘 안 터진다"에 기대지 않는다
    public static void assertOwnedBy(String pathOrKey, String publicId) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return;
        }
        String key = normalizeManagedKey(pathOrKey);
        if (!isManagedKey(key)) {
            return;
        }
        if (key.contains("..") || key.contains("//") || key.contains("\\")) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
        if (!key.startsWith(userRoot(publicId))) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
    }

    // 관리 대상일 때만 순수 key로 정규화하고, 아니면 원본을 그대로 돌려준다.
    // normalizeToKey와 계약이 다르다: 저쪽은 "반드시 우리 key"라는 전제의 엄격한 연산(실패=GEN-002),
    // 이쪽은 외부 URL·정적 경로가 섞여 들어오는 입력을 위한 실패 없는 연산 — 남의 URL을 파괴하지 않는다.
    public static String normalizeManagedKey(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            return pathOrKey;
        }
        String value = pathOrKey.strip();
        if (value.startsWith("s3://")) {
            String path = extractPath(value);
            return (path == null || path.isBlank()) ? value : path;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            String path = extractPath(value);
            return isManagedKey(path) ? path : value;
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    // URI에서 앞 슬래시를 뗀 path를 추출. 파싱 불가면 null (호출부가 원본 유지로 처리)
    private static String extractPath(String uri) {
        try {
            String path = URI.create(uri).getPath();
            if (path == null) {
                return null;
            }
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 입력(s3://, http(s):// URL, key)을 순수 S3 object key로 정규화. key를 추출할 수 없으면 GEN-002
    public static String normalizeToKey(String pathOrKey) {
        if (pathOrKey == null || pathOrKey.isBlank()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "S3 path or key must not be blank.");
        }
        String value = pathOrKey.strip();
        if (value.startsWith("s3://") || value.startsWith("http://") || value.startsWith("https://")) {
            String path;
            try {
                path = URI.create(value).getPath();
            } catch (IllegalArgumentException e) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "Cannot extract S3 key from the given path.");
            }
            if (path == null || path.isBlank() || path.equals("/")) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "Cannot extract S3 key from the given path.");
            }
            return path.startsWith("/") ? path.substring(1) : path;
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
