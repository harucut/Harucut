package com.harucut.subscription.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SubscriptionErrorCode implements ErrorCode {

    PLAN_HISTORY_RETENTION_EXCEEDED("SUBS-002", HttpStatus.FORBIDDEN, "Requested history is beyond the plan's retention period."),
    PLAN_FRAME_RETENTION_EXCEEDED("SUBS-003", HttpStatus.FORBIDDEN, "The number of stored frames exceeds the limit for the current plan."),
    NO_ACTIVE_SUBSCRIPTION("SUBS-004", HttpStatus.NOT_FOUND, "No active subscription found."),
    ALREADY_CANCELED("SUBS-005", HttpStatus.CONFLICT, "The subscription's auto-renewal is already canceled."),
    NO_AUTO_RENEWAL_TO_CANCEL("SUBS-006", HttpStatus.CONFLICT, "There is no auto-renewal to cancel for this subscription."),
    // 스토어 구독 (ADR-0011 §2)
    STORE_VERIFICATION_FAILED("SUBS-007", HttpStatus.BAD_REQUEST, "The store purchase could not be verified."),
    STORE_PURCHASE_OWNED_BY_OTHER("SUBS-008", HttpStatus.CONFLICT, "The store purchase belongs to another account."),
    SUBSCRIBED_ELSEWHERE("SUBS-009", HttpStatus.CONFLICT, "Already subscribed through another payment source."),
    UNKNOWN_STORE_PRODUCT("SUBS-010", HttpStatus.BAD_REQUEST, "Unknown store product.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;
}