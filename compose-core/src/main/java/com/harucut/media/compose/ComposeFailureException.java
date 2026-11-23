package com.harucut.media.compose;

/** 종류가 정해진 합성 실패 — 문구 앞에 코드 머리말을 붙여 Lambda 실패 통지(errorMessage)로도 종류가 전해진다 */
public class ComposeFailureException extends IllegalArgumentException {

    private final ComposeFailureCode code;

    public ComposeFailureException(ComposeFailureCode code, String message) {
        super(code.prefix() + message);
        this.code = code;
    }

    public ComposeFailureException(ComposeFailureCode code, String message, Throwable cause) {
        super(code.prefix() + message, cause);
        this.code = code;
    }

    public ComposeFailureCode code() {
        return code;
    }
}
