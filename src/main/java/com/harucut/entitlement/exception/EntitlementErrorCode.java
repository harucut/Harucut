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
    FEATURE_NOT_ENTITLED("ENT-004", HttpStatus.FORBIDDEN, "The entitlement does not include this feature."),
    // data: { parentQuota, allocated, requested } — allocated 는 이 요청 전 활성 배분 합
    ALLOCATION_EXCEEDS_PARENT("ENT-005", HttpStatus.CONFLICT, "The allocations exceed the parent entitlement's compose quota."),
    // data: { used, reserved, requested }
    ALLOCATION_BELOW_USAGE("ENT-006", HttpStatus.CONFLICT, "The allocation cannot be reduced below what is already used or reserved.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}
