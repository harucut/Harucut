package com.harucut.draft.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DraftErrorCode implements ErrorCode {

    // 다른 기기가 먼저 저장했다 — data 에 서버의 { revision, updatedAt }
    REVISION_CONFLICT("DRAFT-001", HttpStatus.CONFLICT, "The draft was saved from another device."),
    DOCUMENT_TOO_LARGE("DRAFT-002", HttpStatus.CONTENT_TOO_LARGE, "The draft document is too large.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
