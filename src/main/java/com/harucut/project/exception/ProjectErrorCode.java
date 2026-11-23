package com.harucut.project.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProjectErrorCode implements ErrorCode {

    // 보관한 프로젝트에는 새로 행사를 넣지 않는다(기존 행사는 그대로)
    PROJECT_ARCHIVED("PROJECT-001", HttpStatus.CONFLICT, "The project is archived."),
    // 게시한 행사의 프로젝트는 바꾸지 않는다 — 상속 기본값이 바뀌어 운영 중 행사가 예고 없이 달라지지 않게
    EVENT_PUBLISHED("PROJECT-002", HttpStatus.CONFLICT, "The project of a published event cannot be changed.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
