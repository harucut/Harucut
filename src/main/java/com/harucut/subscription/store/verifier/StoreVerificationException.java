package com.harucut.subscription.store.verifier;

/** 서명·번들·환경·상품이 맞지 않는다 — 서비스가 SUBS-007 로 바꾼다. 사유는 로그에만(응답에 싣지 않는다) */
public class StoreVerificationException extends RuntimeException {

    public StoreVerificationException(String message) {
        super(message);
    }

    public StoreVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
