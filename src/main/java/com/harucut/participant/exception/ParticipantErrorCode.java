package com.harucut.participant.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ParticipantErrorCode implements ErrorCode {

    SESSION_INVALID("PART-001", HttpStatus.UNAUTHORIZED, "The participant session is missing, expired or revoked."),
    CONSENT_REQUIRED("PART-002", HttpStatus.FORBIDDEN, "Required participation consent is missing."),
    CAPTURE_RUN_NOT_ACTIVE("PART-003", HttpStatus.CONFLICT, "The capture run is not active."),
    FRAME_NOT_OFFERED("PART-004", HttpStatus.FORBIDDEN, "The frame is not offered to this capture run."),
    // 미검증·다른 회차·이미 다른 작업에 묶인 입력
    INPUT_NOT_USABLE("PART-005", HttpStatus.CONFLICT, "The capture input cannot be used."),
    // 공용 부스 세션에는 이전 결과 목록·재다운로드를 열지 않는다
    BOOTH_SESSION_FORBIDDEN("PART-006", HttpStatus.FORBIDDEN, "This action is not allowed for a booth session."),
    // 행사 발행본이 FIXED 인데 자유 문구를 보냈다(R5 D06)
    FREE_TEXT_NOT_OFFERED("PART-007", HttpStatus.FORBIDDEN, "Free text is not offered for this event."),
    // 문구 검증 실패 — data.reason: EMPTY·TOO_LONG·TOO_MANY_LINES·UNSUPPORTED_CHARACTER·STYLE_NOT_OFFERED·DOES_NOT_FIT
    FREE_TEXT_INVALID("PART-008", HttpStatus.BAD_REQUEST, "The free text cannot be used.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
