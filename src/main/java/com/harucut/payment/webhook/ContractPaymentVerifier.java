package com.harucut.payment.webhook;

import com.harucut.audit.service.AuditLogService;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.payment.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 계약의 PORTONE 증빙을 PortOne API 로 다시 조회해 금액·통화·상점·상태(PAID)를 대조한다 (ADR-0008 §8).
 * 웹훅과, 웹훅보다 증빙이 늦게 등록된 계약을 관리자가 다시 확인할 때 쓴다. <b>실연동 미확인</b>.
 */
@Slf4j
@Component
public class ContractPaymentVerifier {

    private final ContractRepository contractRepository;
    private final ObjectProvider<PortOnePaymentClient> portOneClient;
    private final AuditLogService auditLogService;
    private final PaymentProperties properties;
    private final Clock clock;

    public ContractPaymentVerifier(ContractRepository contractRepository, ObjectProvider<PortOnePaymentClient> portOneClient,
                                   AuditLogService auditLogService, PaymentProperties properties, Clock clock) {
        this.contractRepository = contractRepository;
        this.portOneClient = portOneClient;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 확인하지 못했으면 그 이유. 맞으면(이미 확인된 계약 포함) empty */
    @Transactional
    public Optional<String> verify(Contract contract) {
        PortOnePaymentClient client = portOneClient.getIfAvailable();
        if (client == null) {
            return Optional.of("PortOne 연동이 꺼져 있습니다(payment.webhook.provider).");
        }
        String paymentId = contract.getEvidenceReference();
        PortOnePaymentClient.PortOnePayment payment = client.get(paymentId);
        boolean matches = "PAID".equals(payment.status())
                && Objects.equals(payment.storeId(), properties.portone().storeId())
                && Objects.equals(payment.totalAmount(), contract.getAmount())
                && contract.getCurrency().equalsIgnoreCase(Objects.requireNonNullElse(payment.currency(), ""));
        if (!matches) {
            log.warn("[payment] PortOne {} 계약 {} 대조 불일치 status={} store={} amount={} currency={}", paymentId,
                    contract.getPublicId(), payment.status(), payment.storeId(), payment.totalAmount(), payment.currency());
            return Optional.of("결제가 계약과 맞지 않습니다(status=%s, amount=%s, currency=%s)."
                    .formatted(payment.status(), payment.totalAmount(), payment.currency()));
        }
        // 조건부 UPDATE — 엔티티를 저장하지 않아 동시에 바뀐 상태 등 다른 열을 덮지 않고, 감사 로그도 한 번만
        if (contractRepository.markPaymentVerified(contract.getId(), LocalDateTime.now(clock)) == 1) {
            auditLogService.record(null, contract.getWorkspaceId(), null, "CONTRACT_PAYMENT_VERIFIED", "CONTRACT",
                    contract.getPublicId(), Map.of("paymentId", paymentId));
        }
        return Optional.empty();
    }
}
