package com.harucut.media.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.media.dto.ComposeRequest;
import com.harucut.media.service.ComposeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "네컷 합성")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/media/compose")
public class ComposeController {

    private final ComposeService composeService;

    // 202: 접수했고 결과는 나중이다 — 프론트는 jobId로 폴링한다
    @Operation(
            summary = "네컷 합성 요청",
            description = """
                    원본 4장 + 프레임 → 완성 이미지. **서버가 합성한다** — 프론트 canvas 가 아니다.

                    **전체 흐름**
                    1. 업로드 API 에 `type: FOURCUT_SOURCE` 로 원본 4장을 올린다 (촬영 순서 기억)
                    2. 이 API 를 호출한다 → **202** `{ jobId, status: "PENDING" }`
                    3. `GET /api/auth/user/media/compose/{jobId}` 를 1~2초 간격으로 폴링한다
                    4. `DONE` 이면 `mediaId` 로 보관함·다운로드 API 를 쓴다

                    ⚠️ **응답 본문의 `status` 는 200 인데 HTTP 상태는 202 다.** 봉투의 `status` 는
                    성공이면 항상 200 을 담기 때문이다. "접수됐다"를 판단하려면 **HTTP 상태**를 볼 것.

                    ⚠️ **202 는 접수됐다는 뜻일 뿐 성공이 아니다.** 실제 실패는 폴링에서 `FAILED` 로 온다.
                    콜드 스타트를 포함해 수 초 걸릴 수 있다.

                    ⚠️ **성공하면 원본 4장을 서버가 지운다.** 같은 사진으로 다른 프레임에 합성하려면
                    원본을 다시 올려야 한다.

                    **같은 `idempotencyKey` 로 다시 보내면** 새로 합성하지 않고 기존 작업을 돌려준다.
                    단 프레임·원본·배경색이 다르면 `409 MEDIA-001` 이다 — 내용이 바뀌었으면 새 키를 쓸 것.

                    `GEN-002` 셋은 프레임 쪽 문제라 원본을 다시 올려도 해결되지 않는다 —
                    프레임을 고쳐 저장해야 한다.
                    """)
    @ApiErrors({
            "GEN-002: 구운 텍스트(renderedKey) 없는 TEXT 가 있거나, S3 밖의 자산(정적 경로·외부 URL)을 쓰는 프레임",
            "GEN-021: 남의 경로의 원본 key",
            "SUBS-002: 보관 기간이 지난 프레임",
            "SUBS-003: 보관 한도 밖으로 밀려난 프레임",
            "GEN-031: 없는 프레임이거나 남의 프레임",
            "MEDIA-001: 같은 idempotencyKey 를 다른 프레임·원본·배경색으로 다시 보냄 — 새 키를 만들 것"
    })
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response<ComposeJobResponse> requestCompose(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody @Valid ComposeRequest request) {
        try {
            return Response.ok(composeService.requestCompose(principal.publicId(), request));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 요청이 정확히 동시에 와서 (user, idempotency_key) 유니크에 졌다.
            // 서비스 트랜잭션은 이미 롤백됐으므로 재생 판정은 트랜잭션 밖인 여기서 새로 연다
            ComposeJobResponse winner = composeService.replayAfterConflict(principal.publicId(), request);
            if (winner == null) {
                throw e;
            }
            return Response.ok(winner);
        }
    }

    @Operation(
            summary = "합성 상태 조회 (폴링)",
            description = """
                    1~2초 간격으로 호출한다.

                    `PENDING` 이면 `mediaId`·`failureReason` **키가 아예 없다.**
                    `DONE` 이면 `mediaId`, `FAILED` 면 `failureReason` 이 붙는다 —
                    null 체크가 아니라 필드 존재 체크로 다룰 것.

                    진행률은 없다. 중간 상태(`RUNNING`)도 없다.
                    """)
    @ApiErrors("GEN-031: 없는 작업이거나 남의 작업")
    @GetMapping("/{jobId}")
    public Response<ComposeJobResponse> getJob(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Parameter(description = "합성 작업 ID", example = "12") @PathVariable Long jobId) {
        return Response.ok(composeService.getJob(principal.publicId(), jobId));
    }
}
