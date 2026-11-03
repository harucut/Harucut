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
    LAST_OWNER("MEMBER-004", HttpStatus.CONFLICT, "The last owner cannot be demoted or removed."),
    // 업체(ENTERPRISE) 공간의 멤버는 사진 권한을 받을 수 없다 — 고객 초대 수락·권한 부여·프로젝트 사진 권한 모두
    ENTERPRISE_MEMBER_NO_PHOTOS("MEMBER-005", HttpStatus.FORBIDDEN, "Members of an enterprise workspace cannot receive photo access.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
