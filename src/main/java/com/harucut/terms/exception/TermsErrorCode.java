package com.harucut.terms.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TermsErrorCode implements ErrorCode {

    TERMS_NOT_FOUND("TERMS-001", HttpStatus.NOT_FOUND, "The requested terms do not exist or are inactive."),
    TERMS_CODE_DUPLICATED("TERMS-002", HttpStatus.CONFLICT, "Terms code already exists."),
    REQUIRED_TERMS_CANNOT_WITHDRAW("TERMS-003", HttpStatus.BAD_REQUEST, "Required terms cannot be withdrawn."),
    // 가입은 동의 없이도 된다(가입 직후 자동 로그인이 없어 동의 API 를 못 부른다). 대신 로그인한 뒤
    // 현재 필수 약관에 동의하기 전까지 보호 API 를 이 코드로 막는다 — 프론트는 이 코드에서 재동의 모달을 연다
    TERMS_CONSENT_REQUIRED("TERMS-004", HttpStatus.FORBIDDEN, "Required terms consent is missing or outdated.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
