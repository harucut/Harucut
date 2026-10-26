package com.harucut.payment.webhook;

import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.repository.ContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookService — 중복·결제 대조 연결")
class WebhookServiceTest {

    private static final String PAID = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-1\",\"storeId\":\"store-1\"}}";

    @Mock private WebhookReceiptRepository receiptRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private ContractPaymentVerifier paymentVerifier;

    private WebhookService service;
    private Contract contract;

    @BeforeEach
    void setUp() {
        service = new WebhookService(receiptRepository, contractRepository, paymentVerifier, JsonMapper.builder().build(), Clock.systemUTC());
        given(receiptRepository.recordOnce(any(), anyString(), anyString(), any())).willReturn(1);
        contract = new Contract(1L, "웨딩홀", EntitlementScope.WORKSPACE, List.of(), 1_000_000L, "KRW",
                LocalDateTime.now(), LocalDateTime.now().plusDays(30), EvidenceKind.PORTONE, "pay-1",
                new Contract.Terms(100, null, null, null, Set.of()), 1L);
    }

    @Test
    @DisplayName("이미 기록된 webhook-id(INSERT IGNORE 0행)면 아무것도 하지 않는다")
    void duplicateIgnored() {
        given(receiptRepository.recordOnce(any(), eq("portone"), eq("msg_1"), eq("Transaction.Paid"))).willReturn(0);

        assertThat(service.handle("portone", "msg_1", PAID)).isFalse();
        then(contractRepository).shouldHaveNoInteractions();
        then(paymentVerifier).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("결제 이벤트는 그 paymentId 를 증빙으로 단 계약을 대조기에 넘긴다")
    void verifiesLinkedContract() {
        given(contractRepository.findByEvidenceKindAndEvidenceReference(EvidenceKind.PORTONE, "pay-1")).willReturn(Optional.of(contract));

        assertThat(service.handle("portone", "msg_1", PAID)).isTrue();
        then(paymentVerifier).should().verify(contract);
    }

    @Test
    @DisplayName("계약에 연결되지 않은 결제·결제 이벤트가 아닌 것·webhook-id 없는 mock 은 대조하지 않는다")
    void unlinkedOrOtherEvents() {
        given(contractRepository.findByEvidenceKindAndEvidenceReference(any(), anyString())).willReturn(Optional.empty());
        assertThat(service.handle("portone", "msg_1", PAID)).isTrue();
        assertThat(service.handle("portone", "msg_2", "{\"type\":\"BillingKey.Issued\"}")).isTrue();
        assertThat(service.handle("mock", null, "not json")).isTrue();
        then(paymentVerifier).shouldHaveNoInteractions();
        then(receiptRepository).should().recordOnce(any(), eq("portone"), eq("msg_2"), eq("BillingKey.Issued"));
        then(receiptRepository).should(org.mockito.Mockito.never()).recordOnce(any(), eq("mock"), isNull(), any());
    }
}
