package com.harucut.event.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {

    // 게시 전·일시중지·촬영 종료·기간 밖. 이미 접수한 작업과 결과 수령은 막지 않는다
    CAPTURE_NOT_OPEN("EVENT-001", HttpStatus.CONFLICT, "The event is not open for capture."),
    CHANNEL_NOT_ALLOWED("EVENT-002", HttpStatus.FORBIDDEN, "This participation channel is not allowed for the event."),
    // data.violations: [{ field, reason }]
    PUBLISH_INVALID("EVENT-003", HttpStatus.BAD_REQUEST, "The event settings cannot be published."),
    SLUG_TAKEN("EVENT-004", HttpStatus.CONFLICT, "The event slug is already in use."),
    // data.currentSettingsVersion
    SETTINGS_VERSION_CONFLICT("EVENT-005", HttpStatus.CONFLICT, "The event settings were modified by another save.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
