package com.harucut.media.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MediaErrorCode implements ErrorCode {

    // 같은 멱등키 = 같은 요청이라는 약속을 어겼다. 기존 Job 을 돌려주면 다른 사진·프레임의 결과를
    // 성공으로 받게 되고, 새 Job 을 만들면 멱등키가 의미를 잃는다 — 둘 다 아니라서 거절한다
    IDEMPOTENCY_KEY_REUSED("MEDIA-001", HttpStatus.CONFLICT,
            "The idempotency key was already used with a different request."),

    // 공유 링크가 닫혔거나 처음부터 없다 — 사진 삭제·보관 기간 지남·주인 탈퇴(요청)·정지·틀린 토큰을 가르지 않는다.
    // 이유를 나누면 그 링크가 있었는지, 주인이 어떤 상태인지가 받는 사람에게 샌다
    SHARE_CLOSED("MEDIA-002", HttpStatus.NOT_FOUND, "The share link is closed or does not exist.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
