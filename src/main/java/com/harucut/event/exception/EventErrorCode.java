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
    SETTINGS_VERSION_CONFLICT("EVENT-005", HttpStatus.CONFLICT, "The event settings were modified by another save."),
    // 공간의 OWNER 가 전부 정지 — 새 참여만 막고, 이미 찍은 사람의 결과 수령은 그대로다. 참가자에게 사유는 보이지 않는다
    PARTICIPATION_SUSPENDED("EVENT-006", HttpStatus.CONFLICT, "The event is not accepting participation now.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
