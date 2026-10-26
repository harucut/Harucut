package com.harucut.export.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExportErrorCode implements ErrorCode {

    NOT_READY("EXPORT-001", HttpStatus.CONFLICT, "The export is not ready yet."),
    GONE("EXPORT-002", HttpStatus.GONE, "The export has expired, failed or been invalidated."),
    INVALID_SELECTION("EXPORT-003", HttpStatus.BAD_REQUEST, "The export selection is empty or too large.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
