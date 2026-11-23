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
    ASSET_NOT_USABLE("FRAME-005", HttpStatus.BAD_REQUEST, "The referenced asset cannot be used in this frame."),
    // 이 프레임의 발행본을 행사가 쓰고 있다 — 행사 경험을 다시 게시해 빼거나 행사를 정리한 뒤 지운다
    VERSION_IN_USE("FRAME-006", HttpStatus.CONFLICT, "A published version of this frame is used by an event."),
    WORKSPACE_FRAME_LIMIT("FRAME-007", HttpStatus.FORBIDDEN, "The workspace has reached its frame limit."),
    // 자유 사진 칸 렌더가 아직 켜지지 않았다(compose.free-cells.enabled) — 저장·발행본은 되고 게시·합성만 막는다(ADR-0012)
    FREE_CELLS_NOT_RENDERABLE("FRAME-008", HttpStatus.CONFLICT, "Frames with free photo cells cannot be composed yet.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
