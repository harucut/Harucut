package com.harucut.payment.controller;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.repository.ContractRepository;
import com.harucut.entitlement.service.ContractService;
import com.harucut.payment.webhook.ContractPaymentVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "계약·이용권 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class ContractPaymentAdminController {

    private final ContractRepository contractRepository;
    private final ContractPaymentVerifier paymentVerifier;
    private final ContractService contractService;

    @Operation(summary = "계약 결제 다시 확인",
            description = "PORTONE 증빙을 PortOne API 로 다시 조회해 대조한다. 웹훅이 증빙 등록보다 먼저 와서 확인되지 않은 계약에 쓴다. "
                    + "맞으면 paymentVerifiedAt 이 채워진 계약을 돌려준다(이미 확인된 계약도 그대로 200). 실연동 미확인.")
    @ApiErrors({"GEN-002: PORTONE 증빙이 없음·PortOne 연동 꺼짐·결제가 계약과 맞지 않음", "GEN-031: 없는 계약"})
    @PostMapping("/contracts/{contractId}/payment-verification")
    public Response<EntitlementDtos.Contract> verifyPayment(@Parameter(description = "계약 public id") @PathVariable String contractId) {
        Contract contract = contractRepository.findByPublicId(contractId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        if (contract.getEvidenceKind() != EvidenceKind.PORTONE) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "PortOne 결제 증빙이 없는 계약입니다.");
        }
        paymentVerifier.verify(contract).ifPresent(reason -> {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, reason);
        });
        return Response.ok(contractService.get(contractId));
    }
}
