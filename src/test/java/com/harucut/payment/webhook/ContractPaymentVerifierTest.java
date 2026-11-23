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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
@DisplayName("ContractPaymentVerifier — PortOne 재조회 대조")
class ContractPaymentVerifierTest {

    @Mock private ContractRepository contractRepository;
    @Mock private ObjectProvider<PortOnePaymentClient> clientProvider;
    @Mock private PortOnePaymentClient client;
    @Mock private AuditLogService auditLogService;

    private ContractPaymentVerifier verifier;
    private Contract contract;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties(new PaymentProperties.Gateway("mock"), new PaymentProperties.Mock(false, false), 3,
                new PaymentProperties.Webhook("portone", 300), new PaymentProperties.PortOne("whsec_x", "api", "store-1", "https://api.portone.io"));
        verifier = new ContractPaymentVerifier(contractRepository, clientProvider, auditLogService, properties, Clock.systemUTC());
        given(clientProvider.getIfAvailable()).willReturn(client);
        contract = new Contract(1L, "웨딩홀", EntitlementScope.WORKSPACE, List.of(), 1_000_000L, "KRW",
                LocalDateTime.now(), LocalDateTime.now().plusDays(30), EvidenceKind.PORTONE, "pay-1",
                new Contract.Terms(100, null, null, null, Set.of()), 1L);
    }

    @Test
    @DisplayName("PAID·상점·금액·통화가 맞으면 조건부 UPDATE 로 확인하고 처음일 때만 감사 로그")
    void verifiesMatchingContract() {
        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "store-1", 1_000_000L, "krw"));
        given(contractRepository.markPaymentVerified(any(), any())).willReturn(1, 0);

        assertThat(verifier.verify(contract)).isEmpty();
        assertThat(verifier.verify(contract)).isEmpty();   // 이미 확인됨 — 실패가 아니다
        then(auditLogService).should().record(any(), eq(1L), any(), eq("CONTRACT_PAYMENT_VERIFIED"), eq("CONTRACT"), anyString(), anyMap());
    }

    @Test
    @DisplayName("금액이 다르거나 다른 상점·미결제면 확인하지 않고 이유를 돌려준다 — 본문이 아니라 조회 결과를 믿는다")
    void mismatchNotVerified() {
        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "store-1", 10L, "KRW"));
        assertThat(verifier.verify(contract)).hasValueSatisfying(reason -> assertThat(reason).contains("amount=10"));

        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "PAID", "other", 1_000_000L, "KRW"));
        assertThat(verifier.verify(contract)).isPresent();

        given(client.get("pay-1")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-1", "READY", "store-1", 1_000_000L, "KRW"));
        assertThat(verifier.verify(contract)).isPresent();

        then(contractRepository).should(never()).markPaymentVerified(any(), any());
        then(auditLogService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PortOne 연동이 꺼져 있으면(클라이언트 빈 없음) 조회하지 않고 이유를 돌려준다")
    void clientUnavailable() {
        given(clientProvider.getIfAvailable()).willReturn(null);

        assertThat(verifier.verify(contract)).isPresent();
        then(contractRepository).shouldHaveNoInteractions();
    }
}
