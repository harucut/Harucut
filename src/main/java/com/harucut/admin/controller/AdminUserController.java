package com.harucut.admin.controller;

import com.harucut.admin.dto.AdminUserDtos;
import com.harucut.admin.service.AdminUserService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "계정 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "이메일로 계정 찾기", description = "정확히 일치하는 계정. 없으면 빈 목록. 소셜 계정 때문에 같은 이메일이 여러 행일 수 있다.")
    @ApiErrors("GEN-004: email 이 없다")
    @GetMapping
    public Response<List<AdminUserDtos.UserItem>> find(@Parameter(description = "이메일", example = "a@b.c") @RequestParam String email) {
        return Response.ok(adminUserService.find(email));
    }

    @Operation(summary = "계정 정지",
            description = """
                    모든 refresh 세션을 끊는다. 정지 계정은 다시 로그인할 수 있지만(`userStatus: BLOCKED`) 마이페이지와 탈퇴만 되고, 그 밖의 보호 API 는 403 `AUTH-042` 다.
                    정지 전에 받은 access 토큰은 관리·사진·행사 경로에서 바로 403 `AUTH-042`, 그 밖의 API 에서는 만료(30분)까지 남는다.
                    이미 정지면 그대로 답한다.
                    """)
    @ApiErrors({
            "GEN-003: 사유가 없거나 500자를 넘는다",
            "GEN-021: 자기 자신이다",
            "GEN-031: 없는 계정",
            "AUTH-005: 탈퇴 요청 중인 계정 — 유예 뒤 삭제된다"
    })
    @PostMapping("/{userId}/block")
    public Response<AdminUserDtos.StatusResponse> block(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @Parameter(description = "사용자 public id") @PathVariable String userId,
                                                        @RequestBody @Valid AdminUserDtos.StatusRequest request) {
        return Response.ok(adminUserService.block(principal.publicId(), userId, request.reason()));
    }

    @Operation(summary = "정지 해제", description = "정지 상태의 토큰도 다음 요청부터 풀린다. 정지 상태가 아니면 그대로 답한다. 탈퇴 요청 중이면 상태를 바꾸지 않는다.")
    @ApiErrors({"GEN-003: 사유가 없거나 500자를 넘는다", "GEN-031: 없는 계정"})
    @PostMapping("/{userId}/unblock")
    public Response<AdminUserDtos.StatusResponse> unblock(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @Parameter(description = "사용자 public id") @PathVariable String userId,
                                                          @RequestBody @Valid AdminUserDtos.StatusRequest request) {
        return Response.ok(adminUserService.unblock(principal.publicId(), userId, request.reason()));
    }
}
