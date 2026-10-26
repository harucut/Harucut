package com.harucut.payment.webhook;

import com.harucut.audit.service.AuditLogService;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.payment.config.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookService — 중복·결제 대조")
class WebhookServiceTest {

    private static final String PAID = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-1\",\"storeId\":\"store-1\"}}";

    @Mock private WebhookReceiptRepository receiptRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private ObjectProvider<PortOnePaymentClient> clientProvider;
    @Mock private PortOnePaymentClient client;
    @Mock private AuditLogService auditLogService;

    private WebhookService service;
    private Contract contract;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties(new PaymentProperties.Gateway("mock"), new PaymentProperties.Mock(false, false), 3,
                new PaymentProperties.Webhook("portone", 300), new PaymentProperties.PortOne("whsec_x", "api", "store-1", "https://api.portone.io"));
        service = new WebhookService(receiptRepository, contractRepository, clientProvider, auditLogService, properties,
                JsonMapper.builder().build(), Clock.systemUTC());
        given(clientProvider.getObject()).willReturn(client);
        contract = new Contract(1L, "웨딩홀", EntitlementScope.WORKSPACE, List.of(), 1_000_000L, "KRW",
                LocalDateTime.now(), LocalDateTime.now().plusDays(30), EvidenceKind.PORTONE, "pay-1",
                new Contract.Terms(100, null, null, null, Set.of()), 1L);
    }

    @Test
    @DisplayName("이미 처리한 webhook-id 는 아무것도 하지 않는다")
    void duplicateIgnored() {
        given(receiptRepository.existsByProviderAndWebhookId("portone", "msg_1")).willReturn(true);

        assertThat(service.handle("portone", "msg_1", PAID)).isFalse();
        then(contractRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("결제 조회가 계약과 맞으면(PAID·상점·금액·통화) 결제 확인을 남기고 감사 로그")
    void verifiesMatchingContract() {
        given(contractRepository.findByEvidenceKindAndEvidenceReference(EvidenceKind.PORTONE, "pay-1")).willReturn(Optional.of(contract));
        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "store-1", 1_000_000L, "KRW"));

        assertThat(service.handle("portone", "msg_1", PAID)).isTrue();
        assertThat(contract.getPaymentVerifiedAt()).isNotNull();
        then(auditLogService).should().record(any(), eq(1L), any(), eq("CONTRACT_PAYMENT_VERIFIED"), eq("CONTRACT"), anyString(), anyMap());
        then(receiptRepository).should().saveAndFlush(any(WebhookReceipt.class));
    }

    @Test
    @DisplayName("금액이 다르거나 다른 상점이면 확인하지 않는다 — 본문이 아니라 조회 결과를 믿는다")
    void mismatchNotVerified() {
        given(contractRepository.findByEvidenceKindAndEvidenceReference(EvidenceKind.PORTONE, "pay-1")).willReturn(Optional.of(contract));
        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "store-1", 10L, "KRW"));
        service.handle("portone", "msg_1", PAID);
        assertThat(contract.getPaymentVerifiedAt()).isNull();

        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "other", 1_000_000L, "KRW"));
        service.handle("portone", "msg_2", PAID);
        assertThat(contract.getPaymentVerifiedAt()).isNull();
        then(auditLogService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("계약에 연결되지 않은 결제는 조회하지 않는다, 결제 이벤트가 아니면 기록만")
    void unlinkedOrOtherEvents() {
        given(contractRepository.findByEvidenceKindAndEvidenceReference(any(), anyString())).willReturn(Optional.empty());
        assertThat(service.handle("portone", "msg_1", PAID)).isTrue();
        assertThat(service.handle("portone", "msg_2", "{\"type\":\"BillingKey.Issued\"}")).isTrue();
        assertThat(service.handle("mock", null, "not json")).isTrue();
        then(client).should(never()).get(anyString());
    }
}
