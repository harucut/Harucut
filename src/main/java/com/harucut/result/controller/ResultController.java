package com.harucut.result.controller;

import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.result.dto.ResultStatusResponse;
import com.harucut.result.service.ResultPrincipal;
import com.harucut.result.service.ResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "행사 결과 수령")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public/results")
public class ResultController {

    private final ResultService resultService;

    @Operation(summary = "결과 상태",
            description = """
                    `Authorization: Bearer <resultToken>` 으로만 받는다 — 경로·쿼리에 토큰을 싣지 않는다.
                    첫 조회로 소모하지 않는다(여러 명이 같은 QR 을 스캔한다). 응답은 캐시되지 않고 리퍼러를 보내지 않는다.
                    """)
    @ApiErrors("RESULT-001: 토큰이 없거나 무효·회수")
    @GetMapping
    public Response<ResultStatusResponse> status(@AuthenticationPrincipal ResultPrincipal principal) {
        return Response.ok(resultService.status(principal));
    }

    @Operation(summary = "다운로드 URL", description = "짧은 GET URL(기본 5분). 요청할 때마다 보관·상태를 다시 본다.")
    @ApiErrors({
            "RESULT-001: 토큰이 없거나 무효·회수",
            "RESULT-002: 보관 기한이 지났다",
            "RESULT-003: 아직 준비되지 않았다"
    })
    @PostMapping("/download")
    public Response<DownloadUrlResponse> download(@AuthenticationPrincipal ResultPrincipal principal) {
        return Response.ok(resultService.download(principal));
    }
}
