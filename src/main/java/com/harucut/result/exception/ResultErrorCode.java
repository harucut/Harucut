package com.harucut.result.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ResultErrorCode implements ErrorCode {

    TOKEN_INVALID("RESULT-001", HttpStatus.UNAUTHORIZED, "The result token is invalid or revoked."),
    EXPIRED("RESULT-002", HttpStatus.GONE, "The result retention period has ended."),
    NOT_READY("RESULT-003", HttpStatus.CONFLICT, "The result is not ready yet.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
