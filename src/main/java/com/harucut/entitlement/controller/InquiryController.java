package com.harucut.entitlement.controller;

import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "문의")
@RestController
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "행사·기업 문의 접수", description = "로그인 없이. 같은 IP 분당 5회.")
    @ApiErrors("GEN-061: 요청이 너무 잦다")
    @PostMapping("/api/public/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EntitlementDtos.InquiryCreated> create(@RequestBody @Valid EntitlementDtos.InquiryRequest request,
                                                           HttpServletRequest servletRequest) {
        return Response.ok(inquiryService.create(request, servletRequest.getRemoteAddr()));
    }
}
