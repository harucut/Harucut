package com.harucut.payment.webhook;

import com.harucut.audit.service.AuditLogService;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.payment.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 검증을 통과한 웹훅의 처리 (ADR-0008 §8). 같은 webhook-id 는 한 번만(수신 기록 유니크).
 *
 * <p>PortOne 결제 이벤트({@code Transaction.*})는 본문의 값이 아니라 PortOne API 로 다시 조회한 결제를 기준으로,
 * 그 paymentId 를 증빙으로 단 계약과 금액·통화·상점·상태(PAID)를 대조한다. 맞으면 계약에 결제 확인 시각을 남긴다.
 * 개인 구독 결제 흐름은 바꾸지 않는다. <b>실연동 미확인</b> — 실제 웹훅 본문·결제 응답 모양은 키를 받은 뒤 확인한다.
 */
@Slf4j
@Service
public class WebhookService {

    private final WebhookReceiptRepository receiptRepository;
    private final ContractRepository contractRepository;
    private final ObjectProvider<PortOnePaymentClient> portOneClient;
    private final AuditLogService auditLogService;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookService(WebhookReceiptRepository receiptRepository, ContractRepository contractRepository,
                          ObjectProvider<PortOnePaymentClient> portOneClient, AuditLogService auditLogService,
                          PaymentProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.receiptRepository = receiptRepository;
        this.contractRepository = contractRepository;
        this.portOneClient = portOneClient;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @return 처리했으면 true, 이미 처리한 webhook-id 면 false */
    @Transactional
    public boolean handle(String provider, String webhookId, String rawBody) {
        JsonNode body = parse(rawBody);
        String type = body == null ? null : body.path("type").asString(null);
        if (webhookId != null) {
            if (receiptRepository.existsByProviderAndWebhookId(provider, webhookId)) {
                return false;
            }
            try {
                receiptRepository.saveAndFlush(new WebhookReceipt(LocalDateTime.now(clock), provider, webhookId,
                        type == null ? null : type.substring(0, Math.min(60, type.length()))));
            } catch (DataIntegrityViolationException e) {
                return false;   // 같은 웹훅이 동시에 두 번 들어와 다른 쪽이 먼저 기록했다
            }
        }
        if (!"portone".equals(provider) || type == null || !type.startsWith("Transaction.")) {
            log.info("[payment] 웹훅 수신 provider={} type={} bodyLength={}", provider, type, rawBody.length());
            return true;
        }
        String paymentId = body.path("data").path("paymentId").asString(null);
        if (paymentId != null) {
            verifyContractPayment(paymentId);
        }
        return true;
    }

    // 결제 조회 → 그 paymentId 를 증빙으로 단 계약과 대조. 맞지 않으면 바꾸지 않고 남긴다
    private void verifyContractPayment(String paymentId) {
        Optional<Contract> contract = contractRepository.findByEvidenceKindAndEvidenceReference(EvidenceKind.PORTONE, paymentId);
        if (contract.isEmpty()) {
            log.info("[payment] PortOne {} — 연결된 계약 없음(개인 결제이거나 아직 증빙 미등록)", paymentId);
            return;
        }
        PortOnePaymentClient.PortOnePayment payment = portOneClient.getObject().get(paymentId);
        Contract target = contract.get();
        boolean matches = "PAID".equals(payment.status())
                && Objects.equals(payment.storeId(), properties.portone().storeId())
                && Objects.equals(payment.totalAmount(), target.getAmount())
                && target.getCurrency().equalsIgnoreCase(Objects.requireNonNullElse(payment.currency(), ""));
        if (!matches) {
            log.warn("[payment] PortOne {} 계약 {} 대조 불일치 status={} store={} amount={} currency={}", paymentId,
                    target.getPublicId(), payment.status(), payment.storeId(), payment.totalAmount(), payment.currency());
            return;
        }
        if (target.markPaymentVerified(LocalDateTime.now(clock))) {
            auditLogService.record(null, target.getWorkspaceId(), null, "CONTRACT_PAYMENT_VERIFIED", "CONTRACT",
                    target.getPublicId(), Map.of("paymentId", paymentId));
        }
    }

    private JsonNode parse(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
