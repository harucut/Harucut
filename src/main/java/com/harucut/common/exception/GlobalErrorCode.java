package com.harucut.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {

    // 400
    BAD_REQUEST("GEN-001", HttpStatus.BAD_REQUEST, "Bad request."),
    INVALID_INPUT_VALUE("GEN-002", HttpStatus.BAD_REQUEST, "Invalid input value."),
    VALIDATION_FAILED("GEN-003", HttpStatus.BAD_REQUEST, "Validation failed."),
    MISSING_PARAMETER("GEN-004", HttpStatus.BAD_REQUEST, "Missing request parameter."),
    TYPE_MISMATCH("GEN-005", HttpStatus.BAD_REQUEST, "Type mismatch error."),
    JSON_PARSE_ERROR("GEN-006", HttpStatus.BAD_REQUEST, "Failed to parse JSON body."),

    // 401 / 403 / 404 / 405 / 415
    UNAUTHORIZED("GEN-011", HttpStatus.UNAUTHORIZED, "Unauthorized access."),
    FORBIDDEN("GEN-021", HttpStatus.FORBIDDEN, "Access denied."),
    NOT_FOUND("GEN-031", HttpStatus.NOT_FOUND, "Resource not found."),
    METHOD_NOT_ALLOWED("GEN-041", HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed."),
    UNSUPPORTED_MEDIA_TYPE("GEN-051", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type."),
    // 공개 경로(참가 세션 발급·부스 연결·결과 수령)의 빈도 제한
    TOO_MANY_REQUESTS("GEN-061", HttpStatus.TOO_MANY_REQUESTS, "Too many requests."),

    // 500
    INTERNAL_SERVER_ERROR("GEN-091", HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
