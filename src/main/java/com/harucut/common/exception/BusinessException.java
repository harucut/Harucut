package com.harucut.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    // 오류 응답의 data 에 실을 값(선택). 충돌 응답의 현재 version 처럼 클라이언트가 다음 행동을 정하는 데 쓴다
    private final Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String logMessage) {
        this(errorCode, logMessage, null);
    }

    public BusinessException(ErrorCode errorCode, String logMessage, Object data) {
        super(logMessage);
        this.errorCode = errorCode;
        this.data = data;
    }
}
