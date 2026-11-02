package com.harucut.auth.controller;

import com.harucut.auth.dto.*;
import com.harucut.auth.password.PasswordChangeService;
import com.harucut.auth.password.PasswordResetService;
import com.harucut.auth.service.AuthRateLimits;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증 · 비밀번호")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/harucut")
public class PasswordController {

    private final PasswordChangeService passwordChangeService;
    private final PasswordResetService passwordResetService;
    private final AuthRateLimits authRateLimits;

    @Operation(
            summary = "재설정 코드 발송",
            description = """
                    비밀번호를 잊었을 때. 가입된 이메일로 6자리 코드를 보낸다. 코드는 **5분** 유효하다.

                    가입용 코드와 마찬가지로 **60초 쿨다운**이 걸린다.

                    소셜로 가입한 계정은 비밀번호가 없으므로 `AUTH-020`(계정 없음)이 나온다.
                    """)
    @ApiErrors({
            "AUTH-040: 같은 이메일로 60초 안에 재요청",
            "AUTH-020: 그 이메일로 가입된 계정이 없음 (소셜 전용 계정 포함)",
            "AUTH-090: 메일 발송 실패. 쿨다운은 풀린다",
            "GEN-061: 같은 IP 의 메일 요청이 너무 많음(10분에 5회) 또는 서비스 일일 발송 상한"
    })
    @PostMapping("/reset/password/code")
    public Response<Void> sendResetCode(@Valid @RequestBody SendResetCodeRequest request, HttpServletRequest servletRequest) {
        authRateLimits.checkMailRequest(servletRequest);
        passwordResetService.sendResetCode(request.email());
        return Response.ok();
    }

    @Operation(
            summary = "재설정 코드 검증 → 리셋 토큰",
            description = """
                    코드가 맞으면 **10분짜리 리셋 토큰**을 준다. 이 토큰으로 새 비밀번호를 설정한다.
                    코드는 이 시점에 소모되므로 같은 코드를 두 번 쓸 수 없다.

                    코드 비교는 대소문자를 가리지 않는다.
                    """)
    @ApiErrors("AUTH-003: 코드가 다르거나 만료됨")
    @PostMapping("/reset/password/verification")
    public Response<ResetTokenResponse> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        String resetToken = passwordResetService.verifyResetCode(request.email(), request.code());
        return Response.ok(new ResetTokenResponse(resetToken));
    }

    @Operation(
            summary = "비밀번호 재설정",
            description = """
                    리셋 토큰으로 새 비밀번호를 설정한다. 토큰은 한 번 쓰면 사라진다.

                    ⚠️ **성공하면 그 계정의 저장된 refresh 토큰이 지워진다.** 다른 기기에 로그인돼 있었다면
                    그 세션은 다음 재발급 시점에 끊긴다(`AUTH-011`). 비밀번호를 잊었다는 것은
                    계정이 탈취됐을 수도 있다는 뜻이라, 기존 세션을 남겨두지 않는다.

                    **이 API 는 토큰을 주지 않는다.** 재설정 후 로그인 화면으로 보낼 것.
                    """)
    @ApiErrors({
            "AUTH-011: 리셋 토큰이 없거나 만료됐거나 이미 사용됨",
            "AUTH-020: 토큰에 묶인 계정이 사라짐"
    })
    @PatchMapping("/reset/password")
    public Response<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.resetToken(), request.newPassword());
        return Response.ok();
    }

    @Operation(
            summary = "비밀번호 변경",
            description = """
                    로그인한 상태에서 현재 비밀번호를 확인하고 바꾼다.

                    **소셜 계정에는 이 메뉴를 보여주지 말 것.** `GET /api/auth/user/info` 의 `loginPlatform`
                    이 `HARUCUT` 이 아니면 비밀번호가 없다. 그래도 호출하면 `AUTH-008` 이다.

                    재설정과 달리 **여기서는 세션을 끊지 않는다.** 본인이 현재 비밀번호를 알고 있는 상황이라
                    다른 기기를 강제로 로그아웃시킬 이유가 없다고 봤다.
                    """)
    @ApiErrors({
            "AUTH-008: 소셜 계정이라 비밀번호가 없음",
            "AUTH-002: 현재 비밀번호가 틀림",
            "AUTH-020: 계정을 찾을 수 없음"
    })
    @PatchMapping("/change/password")
    public Response<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        passwordChangeService.changePassword(
                principal.publicId(), request.oldPassword(), request.newPassword());
        return Response.ok();
    }
}
