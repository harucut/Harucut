package com.harucut.admin.controller;

import com.harucut.admin.dto.AdminSearchDtos;
import com.harucut.admin.service.AdminSearchService;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "계약·이용권 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSearchController {

    private final AdminSearchService adminSearchService;

    @Operation(summary = "조직 공간 찾기", description = "이름 또는 활성 OWNER 이메일 부분 일치. 개인 공간은 나오지 않는다. 최신순 최대 20건.")
    @ApiErrors("GEN-002: 검색어가 두 글자 미만")
    @GetMapping("/workspaces")
    public Response<List<AdminSearchDtos.WorkspaceItem>> workspaces(
            @Parameter(description = "검색어(두 글자 이상)") @RequestParam String query) {
        return Response.ok(adminSearchService.workspaces(query));
    }

    @Operation(summary = "행사 찾기", description = "slug 또는 이름 부분 일치. 최신순 최대 20건. 공간 id·이름이 함께 나온다.")
    @ApiErrors("GEN-002: 검색어가 두 글자 미만")
    @GetMapping("/events")
    public Response<List<AdminSearchDtos.EventItem>> events(
            @Parameter(description = "검색어(두 글자 이상)") @RequestParam String query) {
        return Response.ok(adminSearchService.events(query));
    }
}
