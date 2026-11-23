package com.harucut.auth.security;

import com.harucut.common.response.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * @PreAuthorize 가 없어서 anyRequest() 규칙까지 내려가는 엔드포인트.
 * 필터 레벨(AuthorizationFilter) 인가와 AccessDeniedHandler 를 검증하는 데 쓴다.
 * @PreAuthorize 가 붙은 엔드포인트로는 이걸 검증할 수 없다 — 거기서 나는 403은
 * 메서드 시큐리티가 만든 것이고, GlobalExceptionHandler 가 응답을 쓴다.
 */
@RestController
@RequestMapping("/fixture/plain")
class PlainApiFixtureController {

    @GetMapping
    public Response<Void> get() {
        return Response.ok();
    }

    // 쿠키 인증 변경 요청의 출처 검사(OriginCheckFilter) 연결을 본다
    @PostMapping
    public Response<Void> post() {
        return Response.ok();
    }
}
