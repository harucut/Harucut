package com.harucut.auth.security;

import com.harucut.common.response.Response;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * @PreAuthorize 를 일부러 뺀 관리자 경로. "새 관리자 컨트롤러가 애노테이션을 빠뜨린" 상황을 흉내 낸다.
 * 여기서 나는 403 은 URL 규칙(SecurityPaths.ADMIN)이 만든 것이어야 한다.
 *
 * @Hidden: 테스트 클래스도 @SpringBootTest 의 스캔에 걸려 관리자 문서 그룹에 섞인다(OpenApiDocumentTest).
 */
@Hidden
@RestController
@RequestMapping("/api/admin/fixture")
class AdminApiFixtureController {

    @GetMapping
    public Response<Void> get() {
        return Response.ok();
    }
}
