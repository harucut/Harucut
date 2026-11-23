package com.harucut.event.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PhotoErrorCode implements ErrorCode {

    // 행사는 볼 수 있지만(운영자이거나 다른 사진 권한이 있다) 이 동작의 사진 권한이 없다
    PERMISSION_REQUIRED("PHOTO-001", HttpStatus.FORBIDDEN, "You do not have this photo permission for the event.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
