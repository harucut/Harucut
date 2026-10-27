package com.harucut.push.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.push.dto.DeviceRegisterRequest;
import com.harucut.push.dto.DeviceUnregisterRequest;
import com.harucut.push.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 토큰은 경로·쿼리에 싣지 않는다 — 접근 로그에 남지 않게 본문으로만 받는다(해제도 DELETE 가 아니라 POST)
@Tag(name = "푸시 기기")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/devices")
public class DeviceController {

    private final DeviceTokenService deviceTokenService;

    @Operation(
            summary = "푸시 기기 등록",
            description = """
                    앱의 Expo 푸시 토큰을 내 계정에 묶는다. **멱등**이다 — 같은 토큰을 다시 보내면 등록 시각만 갱신된다.
                    다른 계정에 묶여 있던 토큰이면 내 계정으로 **옮긴다**(한 기기는 마지막으로 로그인한 계정만 받는다).

                    계정당 10대까지. 넘으면 가장 오래 등록하지 않은 기기부터 지운다.

                    발송 종류(`data.type`): `COMPOSE_DONE`(jobId) · `COMPOSE_FAILED`(jobId, failureCode) ·
                    `PAYMENT_FAILED` · `SUBSCRIPTION_EXPIRING`. data 값은 전부 문자열이다.
                    """)
    @ApiErrors({
            "GEN-003: token 이 Expo 형식이 아니거나 platform 이 ios·android 가 아님",
            "GEN-031: 토큰은 유효한데 그 계정이 사라짐"
    })
    @PostMapping
    public Response<Void> register(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @Valid @RequestBody DeviceRegisterRequest request) {
        deviceTokenService.register(principal.publicId(), request);
        return Response.ok();
    }

    @Operation(
            summary = "푸시 기기 해제",
            description = """
                    로그아웃 직전에 부른다. 내 계정에 묶인 그 토큰만 지우고, **없어도 200** 이다.
                    탈퇴 요청·정지 상태에서도, 약관 동의가 빠져 있어도 부를 수 있다.
                    """)
    @ApiErrors("GEN-003: token 이 비었음")
    @PostMapping("/unregister")
    public Response<Void> unregister(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @Valid @RequestBody DeviceUnregisterRequest request) {
        deviceTokenService.unregister(principal.publicId(), request.token());
        return Response.ok();
    }
}
