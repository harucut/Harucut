package com.harucut.auth.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.auth.service.UserExitService;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증 · 탈퇴 · 복구")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/harucut")
public class UserExitController {

    private final UserExitService userExitService;
    private final CookieManager cookieManager;

    @Operation(
            summary = "탈퇴 요청",
            description = """
                    바로 지우지 않는다. 상태를 `DELETED_REQUESTED` 로 바꾸고 **7일 뒤 배치가 실제로 삭제**한다.
                    그 사이에는 복구 API 로 되돌릴 수 있다.

                    응답에 두 쿠키를 만료시키는 `Set-Cookie` 가 실린다 — 즉 **즉시 로그아웃된다.**
                    되돌리려면 다시 로그인해서 복구 API 를 호출해야 한다.

                    이 상태로 로그인하면 로그인 자체는 되지만 일반 API 는 전부 403 이다.

                    정지(`BLOCKED`) 계정도 요청할 수 있다. 7일 뒤 삭제도 같다. 복구하면 `ACTIVE` 가 아니라 `BLOCKED` 로 돌아간다.
                    조직 공간의 마지막 OWNER 면 409 `MEMBER-004` 다.
                    """)
    @ApiErrors({"AUTH-020: 계정을 찾을 수 없음", "MEMBER-004: 조직 공간의 마지막 OWNER"})
    @DeleteMapping("/exit")
    public ResponseEntity<Response<Void>> exit(@AuthenticationPrincipal AuthenticatedUser principal) {
        userExitService.requestExit(principal.publicId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired(CookieManager.ACCESS_TOKEN))
                .header(HttpHeaders.SET_COOKIE, expired(CookieManager.REFRESH_TOKEN))
                .body(Response.ok());
    }

    @Operation(
            summary = "탈퇴 취소 (계정 복구)",
            description = """
                    `DELETED_REQUESTED` 상태에서만 호출할 수 있다. 상태를 `ACTIVE` 로 되돌린다. 탈퇴 요청 전에 정지돼 있었으면 `BLOCKED` 로 돌아간다.

                    ⚠️ **복구에 성공해도 새 토큰을 주지 않는다.** 지금 들고 있는 토큰에는 옛 상태가 박혀 있어
                    일반 API 가 여전히 403 이다. **복구 직후 로그아웃시키고 다시 로그인하게 할 것.**

                    이미 `ACTIVE` 인 계정이 호출하면 403 이다.
                    """)
    @ApiErrors({
            "AUTH-007: 탈퇴 요청 상태가 아님",
            "AUTH-020: 계정을 찾을 수 없음"
    })
    @PostMapping("/reactivate")
    @PreAuthorize("hasRole('DELETED_REQUESTED')")
    public Response<Void> reactivate(@AuthenticationPrincipal AuthenticatedUser principal) {
        userExitService.reActivate(principal.publicId());
        return Response.ok();
    }

    private String expired(String name) {
        return cookieManager.createExpiredCookie(name).toString();
    }
}