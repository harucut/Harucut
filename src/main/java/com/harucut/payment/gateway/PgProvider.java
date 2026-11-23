package com.harucut.payment.gateway;

public enum PgProvider {
    MOCK, TOSS,
    /** 결제를 닫아 둔 운영(DisabledPaymentGateway) — 발급이 실패하므로 행에 저장되지 않는다 */
    NONE
}
