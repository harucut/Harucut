package com.harucut.auth.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_CREDENTIALS("AUTH-001", HttpStatus.BAD_REQUEST, "Invalid credentials."),
    INCORRECT_PASSWORD("AUTH-002", HttpStatus.BAD_REQUEST, "Incorrect password."),
    INVALID_VERIFICATION_CODE("AUTH-003", HttpStatus.BAD_REQUEST, "Invalid or expired verification code."),
    EMAIL_NOT_VERIFIED("AUTH-004", HttpStatus.BAD_REQUEST, "Failed to register email."),
    ACCOUNT_PENDING_DELETION("AUTH-005", HttpStatus.BAD_REQUEST, "This account is pending deletion."),
    ACCOUNT_ALREADY_DELETED("AUTH-006", HttpStatus.BAD_REQUEST, "This account has been permanently deleted."),
    NOT_DELETION_TARGET("AUTH-007", HttpStatus.BAD_REQUEST, "This account is not a deletion target."),
    SOCIAL_ACCOUNT_NO_PASSWORD("AUTH-008", HttpStatus.BAD_REQUEST, "Social accounts do not have a password."),
    AUTHENTICATION_FAILED("AUTH-010", HttpStatus.UNAUTHORIZED, "Authentication failed."),
    INVALID_TOKEN("AUTH-011", HttpStatus.UNAUTHORIZED, "Invalid access token."),
    EXPIRED_TOKEN("AUTH-012", HttpStatus.UNAUTHORIZED, "Expired access token."),
    USER_NOT_FOUND("AUTH-020", HttpStatus.NOT_FOUND, "User not found."),
    EMAIL_ALREADY_IN_USE("AUTH-030", HttpStatus.CONFLICT, "This email is already in use."),
    TOO_MANY_REQUESTS("AUTH-040", HttpStatus.TOO_MANY_REQUESTS, "Too many verification requests."),
    // 앱 로그인 일회용 코드 — 없음·만료·이미 씀·PKCE 검증값 불일치를 하나로 묶는다(어느 쪽인지 흘리지 않는다)
    APP_LOGIN_CODE_INVALID("AUTH-041", HttpStatus.BAD_REQUEST, "The app login code is invalid or expired."),
    EMAIL_SEND_FAILED("AUTH-090", HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send verification email."),
    OAUTH2_UNLINK_FAILED("AUTH-091", HttpStatus.INTERNAL_SERVER_ERROR, "Failed to unlink OAuth2 provider account.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
