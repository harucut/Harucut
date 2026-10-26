package com.harucut.workspace.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    INVITATION_EXPIRED("MEMBER-001", HttpStatus.GONE, "The invitation has expired."),
    INVITATION_INVALID("MEMBER-002", HttpStatus.BAD_REQUEST, "The invitation is invalid, already used or revoked."),
    ALREADY_MEMBER("MEMBER-003", HttpStatus.CONFLICT, "The user already has this access."),
    LAST_OWNER("MEMBER-004", HttpStatus.CONFLICT, "The last owner cannot be demoted or removed.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
