package com.harucut.auth.controller;

import com.harucut.auth.dto.SendCodeRequest;
import com.harucut.auth.dto.VerifyCodeRequest;
import com.harucut.auth.email.EmailVerificationService;
import com.harucut.auth.service.AuthRateLimits;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증 · 이메일 인증")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/email-auth")
public class EmailAuthController {

    private final EmailVerificationService emailVerificationService;
    private final AuthRateLimits authRateLimits;

    @Operation(
            summary = "가입용 인증 코드 발송",
            description = """
                    입력한 이메일로 6자리 코드를 보낸다. 코드는 **5분** 유효하다.

                    **같은 이메일로 60초 안에 다시 요청하면 429 다.** 재발송 버튼에 60초 카운트다운을 붙이고,
                    그래도 429 가 오면(탭을 새로 열었다거나) 에러 화면이 아니라 "잠시 후 다시 시도" 안내로 처리할 것.

                    이미 가입된 이메일이면 메일을 보내지 않고 409 로 거절한다 — 마지막 단계까지 가서야
                    중복을 알게 되는 일을 막기 위해서다. **쿨다운을 먼저 검사하므로, 연달아 요청하면
                    가입된 이메일이어도 429 가 먼저 나올 수 있다.**
                    """)
    @ApiErrors({
            "AUTH-040: 같은 이메일로 60초 안에 재요청",
            "AUTH-030: 이미 가입된 이메일 — 로그인으로 안내할 것",
            "AUTH-090: 메일 발송 실패. 쿨다운은 풀리므로 바로 재시도할 수 있다"
    })
    @PostMapping("/code")
    public Response<Void> sendCode(@Valid @RequestBody SendCodeRequest request, HttpServletRequest servletRequest) {
        authRateLimits.checkMailRequest(servletRequest);
        emailVerificationService.sendVerificationCode(request.email());
        return Response.ok();
    }

    @Operation(
            summary = "가입용 인증 코드 검증",
            description = """
                    검증에 성공하면 서버가 "이 이메일은 인증됨"을 **10분간** 기억한다.
                    그 안에 회원가입을 마쳐야 하고, 가입에 한 번 쓰면 소모된다.

                    코드 비교는 대소문자를 가리지 않는다. 앞뒤 공백도 서버가 잘라낸다.
                    """)
    @ApiErrors("AUTH-003: 코드가 다르거나 5분이 지나 만료됨 — 둘을 구분하지 않는다")
    @PostMapping("/verification")
    public Response<Void> verify(@Valid @RequestBody VerifyCodeRequest request) {
        emailVerificationService.verifyCode(request.email(), request.code());
        return Response.ok();
    }
}
