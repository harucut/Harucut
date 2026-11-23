package com.harucut.booth.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BoothErrorCode implements ErrorCode {

    PAIRING_CODE_INVALID("BOOTH-001", HttpStatus.BAD_REQUEST, "The pairing code is wrong or expired."),
    PAIRING_LOCKED("BOOTH-002", HttpStatus.GONE, "The pairing was closed after too many failed attempts."),
    DEVICE_UNAVAILABLE("BOOTH-003", HttpStatus.FORBIDDEN, "The booth device is revoked, unassigned or its token is invalid."),
    RUN_ALREADY_ACTIVE("BOOTH-004", HttpStatus.CONFLICT, "The booth device already has an active capture run."),
    STATE_VERSION_CONFLICT("BOOTH-005", HttpStatus.CONFLICT, "The capture run state version does not match."),
    DEVICE_LIMIT("BOOTH-006", HttpStatus.FORBIDDEN, "The event has reached its booth device limit.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
