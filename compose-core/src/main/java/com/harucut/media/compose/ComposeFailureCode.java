package com.harucut.media.compose;

/**
 * 합성 실패의 종류 — 실행기(서버·Lambda)가 정하고 서버가 작업에 남겨 응답(failureCode)으로 내보낸다. 화면은 이 값으로 문구를 고른다.
 * 실패 문구(failureReason)는 진단용이라 사용자에게 보여 주지 않는다.
 */
public enum ComposeFailureCode {
    /** 촬영 원본이 저장소에 없다(만료·삭제) */
    SOURCE_MISSING,
    /** 촬영 원본을 이미지로 읽을 수 없다(손상·형식) */
    SOURCE_UNREADABLE,
    /** 프레임·배경 자산이 저장소에 없다 */
    ASSET_MISSING,
    /** 프레임·배경 자산을 이미지로 읽을 수 없다 */
    ASSET_UNREADABLE,
    /** 실행 시간 초과 */
    TIMED_OUT,
    /** 그 밖의 렌더 실패 */
    RENDER_FAILED,
    /** 실패 종류를 알리지 않는 실행기(코드 도입 전 Lambda 등) */
    UNKNOWN;

    private static final String MARK = "[";

    /** 실행기가 던지는 문구의 머리말 — 「[SOURCE_UNREADABLE] 이미지 형식을 인식할 수 없다」 */
    public String prefix() {
        return MARK + name() + "] ";
    }

    /**
     * Lambda 실패 통지(errorType·errorMessage)에서 종류를 읽는다. 코드 머리말이 있으면 그 값, 시간 초과 문구면 TIMED_OUT,
     * 그 밖은 UNKNOWN — 코드를 알리지 않는 옛 실행기의 실패를 다른 종류로 꾸미지 않는다
     */
    public static ComposeFailureCode from(String errorType, String errorMessage) {
        if (errorMessage != null && errorMessage.startsWith(MARK)) {
            int end = errorMessage.indexOf(']');
            if (end > 1) {
                try {
                    return valueOf(errorMessage.substring(1, end));
                } catch (IllegalArgumentException ignored) {
                    return UNKNOWN;
                }
            }
        }
        if (errorMessage != null && errorMessage.contains("Task timed out")) {
            return TIMED_OUT;
        }
        return UNKNOWN;
    }
}
