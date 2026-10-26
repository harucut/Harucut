package com.harucut.frame.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FrameErrorCode implements ErrorCode {

    // 없는 프레임과 사용자 프레임 id 조작 시도가 같은 404다 — 관리자에게도 남의 프레임 존재를 확인시켜주지 않는다
    SYSTEM_FRAME_NOT_FOUND("FRAME-001", HttpStatus.NOT_FOUND, "The requested system frame does not exist."),
    // 다른 기기에서 먼저 저장했다 — data 에 { currentVersion }. 다시 불러오거나 그 version 으로 덮어쓴다
    VERSION_CONFLICT("FRAME-002", HttpStatus.CONFLICT, "The frame was modified by another save."),
    TOO_MANY_LAYERS("FRAME-003", HttpStatus.BAD_REQUEST, "The frame has too many layers."),
    // 합성 스냅샷(spec_json)에 담기지 않는 크기 — DB 오류(500)로 죽기 전에 설명 가능한 4xx 로 끊는다
    SPEC_TOO_LARGE("FRAME-004", HttpStatus.BAD_REQUEST, "The frame is too large to compose."),
    // 남의 공간·미검증·용도 불일치 자산
    ASSET_NOT_USABLE("FRAME-005", HttpStatus.BAD_REQUEST, "The referenced asset cannot be used in this frame.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
