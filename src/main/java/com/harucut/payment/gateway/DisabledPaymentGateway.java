package com.harucut.payment.gateway;

import com.harucut.payment.gateway.dto.BillingChargeCommand;
import com.harucut.payment.gateway.dto.BillingKeyResult;
import com.harucut.payment.gateway.dto.IssueBillingKeyCommand;
import com.harucut.payment.gateway.dto.PaymentResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 결제를 닫아 둔 운영 — 실제 PG 구현이 붙기 전(payment.gateway.provider=disabled). 발급·청구가 전부 실패해 주문·구독이 생기지 않는다.
 * 목 게이트웨이는 임의 값으로 유료 구독을 내주므로 운영에서 쓰지 않는다
 */
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "disabled")
public class DisabledPaymentGateway implements PaymentGateway {

    private static final String CODE = "PAYMENT_DISABLED";
    private static final String MESSAGE = "결제가 열려 있지 않다.";

    @Override
    public PgProvider provider() {
        return PgProvider.NONE;
    }

    @Override
    public BillingKeyResult issueBillingKey(IssueBillingKeyCommand command) {
        return BillingKeyResult.failure(CODE, MESSAGE);
    }

    @Override
    public PaymentResult charge(BillingChargeCommand command) {
        return PaymentResult.failure(CODE, MESSAGE);
    }
}
