package com.harucut.text;

/** 렌더러가 문구를 받지 않을 때. 호출자(참가자 서비스)가 PART-008 로 바꾼다. */
public class FreeTextException extends RuntimeException {

    private final FreeTextRejection reason;

    public FreeTextException(FreeTextRejection reason) {
        super(reason.name());
        this.reason = reason;
    }

    public FreeTextRejection reason() {
        return reason;
    }
}
