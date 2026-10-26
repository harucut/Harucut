package com.harucut.payment.webhook;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.entitlement.service.ContractService;
import com.harucut.payment.controller.ContractPaymentAdminController;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/*
 * P2B-7 — 웹훅 수신 기록·결제 확인을 실제 JPA 로 본다(ADR-0008 §8). PortOne 조회만 대역이다(실연동 미확인).
 */
@SpringBootTest(properties = {"payment.portone.store-id=store-1"})
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
@DisplayName("계약 결제 확인·웹훅 중복 — 통합")
class ContractPaymentIntegrationTest {

    @Autowired private WebhookService webhookService;
    @Autowired private ContractService contractService;
    @Autowired private ContractRepository contractRepository;
    @Autowired private ContractPaymentAdminController adminController;
    @Autowired private ContractPaymentVerifier paymentVerifier;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private PortOnePaymentClient portOneClient;

    private User admin;
    private WorkspaceResponse org;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.localUser("admin@harucut.com", "e", "관리자"));
        User owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
    }

    @Test
    @DisplayName("같은 webhook-id 두 번째는 false — 유니크 위반 예외 없이 0행이라 그 트랜잭션이 그대로 커밋된다")
    void duplicateWebhookDoesNotPoisonTransaction() {
        String body = "{\"type\":\"BillingKey.Issued\"}";
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String webhookId = "msg_" + java.util.UUID.randomUUID();

        // 롤백 전용이 됐다면 커밋에서 UnexpectedRollbackException 이 난다
        assertThatCode(() -> separate.executeWithoutResult(status -> {
            assertThat(webhookService.handle("portone", webhookId, body)).isTrue();
            assertThat(webhookService.handle("portone", webhookId, body)).isFalse();
            assertThat(webhookService.handle("mock", webhookId, body)).isTrue();   // 제공자가 다르면 별개
        })).doesNotThrowAnyException();
        Boolean again = separate.execute(status -> webhookService.handle("portone", webhookId, body));
        assertThat(again).isFalse();
    }

    @Test
    @DisplayName("웹훅이 증빙 등록보다 먼저 왔으면 확인되지 않고, 관리자 재확인으로 확인된다(두 번째도 200)")
    void lateEvidenceVerifiedByAdmin() {
        given(portOneClient.get("pay-late")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-late", "PAID", "store-1", 5000L, "KRW"));
        webhookService.handle("portone", "msg_1", "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-late\"}}");

        EntitlementDtos.Contract contract = create("pay-late", 5000L);
        assertThat(contract.paymentVerifiedAt()).isNull();

        assertThat(adminController.verifyPayment(contract.contractId()).data().paymentVerifiedAt()).isNotNull();
        assertThat(adminController.verifyPayment(contract.contractId()).data().paymentVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("관리자 재확인: 금액 불일치·PORTONE 증빙 없음은 GEN-002")
    void adminVerifyRejects() {
        given(portOneClient.get("pay-x")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-x", "PAID", "store-1", 1L, "KRW"));
        EntitlementDtos.Contract mismatched = create("pay-x", 5000L);
        assertThatThrownBy(() -> adminController.verifyPayment(mismatched.contractId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE));

        EntitlementDtos.Contract noEvidence = contractService.create(admin.getPublicId(), request(null, 5000L));
        assertThatThrownBy(() -> adminController.verifyPayment(noEvidence.contractId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE));
        assertThat(contractRepository.findByPublicId(mismatched.contractId()).orElseThrow().getPaymentVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("결제 확인은 확인 시각만 쓴다 — 먼저 읽어 둔(DRAFT) 계약으로 확인해도 그 사이 바뀐 상태(ACTIVE)를 덮지 않는다")
    void verificationDoesNotOverwriteStatus() {
        given(portOneClient.get("pay-race")).willReturn(new PortOnePaymentClient.PortOnePayment("pay-race", "PAID", "store-1", 5000L, "KRW"));
        EntitlementDtos.Contract created = create("pay-race", 5000L);
        Contract stale = contractRepository.findByPublicId(created.contractId()).orElseThrow();
        entityManager.detach(stale);

        contractService.changeStatus(admin.getPublicId(), created.contractId(), ContractStatus.ACTIVE);
        assertThat(paymentVerifier.verify(stale)).isEmpty();
        entityManager.flush();
        entityManager.clear();

        Contract reloaded = contractRepository.findByPublicId(created.contractId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(reloaded.getPaymentVerifiedAt()).isNotNull();
    }

    private EntitlementDtos.Contract create(String paymentId, long amount) {
        return contractService.create(admin.getPublicId(), request(paymentId, amount));
    }

    private EntitlementDtos.ContractRequest request(String paymentId, long amount) {
        return new EntitlementDtos.ContractRequest(org.workspaceId(), "웨딩홀", EntitlementScope.WORKSPACE, null, amount, "KRW",
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                paymentId == null ? null : new EntitlementDtos.Evidence(EvidenceKind.PORTONE, paymentId),
                new EntitlementDtos.Terms(10, null, null, null, Set.of()));
    }
}
