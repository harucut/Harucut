package com.harucut.entitlement.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.service.ContractService;
import com.harucut.entitlement.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "계약·이용권 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class EntitlementAdminController {

    private final InquiryService inquiryService;
    private final ContractService contractService;

    @Operation(summary = "문의 목록", description = "최신 200개.")
    @GetMapping("/inquiries")
    public Response<List<EntitlementDtos.Inquiry>> inquiries() {
        return Response.ok(inquiryService.list());
    }

    @Operation(summary = "문의 상태·메모")
    @ApiErrors("GEN-031: 없는 문의")
    @PatchMapping("/inquiries/{inquiryId}")
    public Response<EntitlementDtos.Inquiry> updateInquiry(@Parameter(description = "문의 public id") @PathVariable String inquiryId,
                                                           @RequestBody @Valid EntitlementDtos.InquiryUpdateRequest request) {
        return Response.ok(inquiryService.update(inquiryId, request));
    }

    @Operation(summary = "계약 만들기", description = "DRAFT 로 만든다. ACTIVE 로 바꿀 때 조건대로 이용권이 생긴다(행사 범위면 행사마다).")
    @ApiErrors({"GEN-002: 개인 공간·다른 공간의 행사·기간 역전", "GEN-031: 없는 공간"})
    @PostMapping("/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EntitlementDtos.Contract> createContract(@AuthenticationPrincipal AuthenticatedUser principal,
                                                             @RequestBody @Valid EntitlementDtos.ContractRequest request) {
        return Response.ok(contractService.create(principal.publicId(), request));
    }

    @Operation(summary = "계약 목록", description = "최신 200개.")
    @GetMapping("/contracts")
    public Response<List<EntitlementDtos.Contract>> contracts() {
        return Response.ok(contractService.list());
    }

    @Operation(summary = "계약 상태 전이",
            description = "DRAFT→ACTIVE(이용권 생성)·CANCELLED, ACTIVE→ENDED·CANCELLED(이용권을 지금 끝냄 — 진행 중 예약은 그대로 확정·반환). 나머지는 ENT-003.")
    @ApiErrors({"ENT-003: 허용되지 않는 전이", "GEN-031: 없는 계약"})
    @PatchMapping("/contracts/{contractId}")
    public Response<EntitlementDtos.Contract> changeContract(@AuthenticationPrincipal AuthenticatedUser principal,
                                                             @Parameter(description = "계약 public id") @PathVariable String contractId,
                                                             @RequestBody @Valid EntitlementDtos.ContractStatusRequest request) {
        return Response.ok(contractService.changeStatus(principal.publicId(), contractId, request.status()));
    }

    @Operation(summary = "수동 이용권 부여(ManualGrant)", description = "체험·보상·파트너. 부여자·사유·기간·한도를 남긴다. 결제를 꾸미지 않는다.")
    @ApiErrors({"GEN-002: 개인 공간·다른 공간의 행사·기간 역전", "GEN-031: 없는 공간"})
    @PostMapping("/entitlements")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EntitlementDtos.Entitlement> grant(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @RequestBody @Valid EntitlementDtos.ManualGrantRequest request) {
        return Response.ok(contractService.grantManually(principal.publicId(), request));
    }

    @Operation(summary = "이용권 목록", description = "최신 200개. 계약에서 생긴 것과 수동 부여 모두.")
    @GetMapping("/entitlements")
    public Response<List<EntitlementDtos.Entitlement>> entitlements() {
        return Response.ok(contractService.entitlements());
    }

    @Operation(summary = "회수 영향 미리보기", description = "이 이용권을 쓰는 운영 중 행사와 아직 종결되지 않은 예약 수.")
    @ApiErrors("GEN-031: 없는 이용권")
    @GetMapping("/entitlements/{entitlementId}/impact")
    public Response<EntitlementDtos.Impact> impact(@Parameter(description = "이용권 public id") @PathVariable String entitlementId) {
        return Response.ok(contractService.impact(entitlementId));
    }

    @Operation(summary = "이용권 회수", description = "사유 필수. 이후 그 이용권으로 새 촬영·게시를 할 수 없다. 진행 중 예약은 그대로 확정·반환된다.")
    @ApiErrors({"ENT-003: 이미 회수됨", "GEN-031: 없는 이용권"})
    @PostMapping("/entitlements/{entitlementId}/revoke")
    public Response<EntitlementDtos.Entitlement> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "이용권 public id") @PathVariable String entitlementId,
                                                        @RequestBody @Valid EntitlementDtos.RevokeRequest request) {
        return Response.ok(contractService.revoke(principal.publicId(), entitlementId, request.reason()));
    }

    @Operation(summary = "행사 배분(기업 총량에서 떼어 주기)",
            description = """
                    활성 공간 범위 이용권(기업 총량)에서 같은 공간의 행사 하나에 합성 한도를 배분한다. 기간·기능·조건·계약은 부모를 따른다.
                    같은 부모의 활성 배분 합이 부모 한도를 넘으면 409 ENT-005(data: parentQuota·allocated·requested).
                    참가 화면은 행사 배분·기업 총량 어느 쪽이 막혀도 ENT-002 하나다.
                    """)
    @ApiErrors({"ENT-005: 배분 합이 기업 총량을 넘는다", "GEN-002: 부모가 기업 총량이 아니거나 다른 공간의 행사·이미 배분이 있다", "GEN-031: 없는 이용권"})
    @PostMapping("/entitlements/{entitlementId}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EntitlementDtos.Entitlement> allocate(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @Parameter(description = "부모(기업 총량) 이용권 public id") @PathVariable String entitlementId,
                                                          @RequestBody @Valid EntitlementDtos.AllocationRequest request) {
        return Response.ok(contractService.allocate(principal.publicId(), entitlementId, request));
    }

    @Operation(summary = "행사 배분량 바꾸기",
            description = "행사 배분만. 늘리면 합 검사(409 ENT-005), 줄이면 이미 사용·예약한 양 아래로는 409 ENT-006(data: used·reserved·requested).")
    @ApiErrors({"ENT-005: 배분 합이 기업 총량을 넘는다", "ENT-006: 사용·예약 아래로 줄임", "GEN-002: 배분이 아니다", "GEN-031: 없는 이용권"})
    @PatchMapping("/entitlements/{entitlementId}")
    public Response<EntitlementDtos.Entitlement> resize(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "행사 배분 이용권 public id") @PathVariable String entitlementId,
                                                        @RequestBody @Valid EntitlementDtos.AllocationResizeRequest request) {
        return Response.ok(contractService.resize(principal.publicId(), entitlementId, request));
    }
}
