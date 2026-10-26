package com.harucut.entitlement.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EntitlementErrorCode implements ErrorCode {

    NO_ENTITLEMENT("ENT-001", HttpStatus.FORBIDDEN, "No entitlement applies to this event."),
    QUOTA_REACHED("ENT-002", HttpStatus.CONFLICT, "The event has reached its compose quota."),
    INVALID_TRANSITION("ENT-003", HttpStatus.CONFLICT, "The contract or entitlement cannot change to this state."),
    FEATURE_NOT_ENTITLED("ENT-004", HttpStatus.FORBIDDEN, "The entitlement does not include this feature.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
