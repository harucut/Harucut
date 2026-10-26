package com.harucut.asset.controller;

import com.harucut.asset.dto.AssetResponse;
import com.harucut.asset.dto.UploadIntentRequest;
import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.service.AssetService;
import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "자산")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/assets")
public class AssetController {

    private final AssetService assetService;

    @Operation(
            summary = "자산 업로드 의도 생성",
            description = """
                    **흐름**
                    1. 이 API → `assetId` · `uploadUrl` · `headers`
                    2. `uploadUrl` 로 **PUT** — 본문은 파일 바이트, 헤더는 `headers` 를 **그대로**
                    3. `POST /api/auth/assets/{assetId}/complete` 로 검증 → `VERIFIED` 여야 프레임에 쓸 수 있다

                    URL 은 기본 10분 동안 유효하다. key 는 서버가 정한다.
                    `If-None-Match: *` 가 있으면 같은 key 에 두 번 올릴 수 없다 — 두 번째 PUT 은 S3 가 412 를 준다.
                    첫 응답을 못 받고 다시 올려 412 를 받았다면 complete 로 확인하면 된다.
                    """)
    @ApiErrors({
            "ASSET-005: 이 경로에서 받지 않는 purpose(CAPTURE_INPUT·PAGE_THEME_ASSET)",
            "GEN-051: 용도에 맞지 않는 형식이거나 파일명 확장자가 형식과 다름",
            "GEN-031: workspaceId 가 없거나 업로드 권한 없음"
    })
    @PostMapping("/upload-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<UploadIntentResponse> createIntent(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @RequestBody @Valid UploadIntentRequest request) {
        return Response.ok(assetService.createIntent(principal.publicId(), request));
    }

    @Operation(
            summary = "업로드 완료 확인",
            description = """
                    S3 객체의 존재·크기, 실제 형식(바이트 머리), 픽셀 수, **끝까지 디코드되는지**를 본다.
                    통과하면 `VERIFIED`, 실패하면 `REJECTED` 로 기록하고 **422 + 그 오류 코드**를 주며 본문 `data` 에 자산이 실린다.

                    멱등이다 — 이미 판정이 끝났으면 그대로 돌려준다(REJECTED 면 같은 422).
                    REJECTED 는 다시 검사하지 않는다. 새 intent 로 다시 올릴 것.
                    """)
    @ApiErrors({
            "ASSET-001: S3 에 객체가 없다 — 아직 안 올렸거나 PUT 실패. 상태는 바뀌지 않는다",
            "ASSET-002: 크기가 선언과 다르거나 10MB 초과",
            "ASSET-003: 선언한 형식이 아니거나 디코드 실패(잘린 파일 포함)",
            "ASSET-004: 픽셀 수 상한 초과",
            "ASSET-006: 검증이 밀려 있다 — 잠시 뒤 같은 요청을 다시",
            "GEN-031: 없는 자산이거나 내 것이 아님"
    })
    @PostMapping("/{assetId}/complete")
    public ResponseEntity<Response<AssetResponse>> complete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "자산 public id", example = "As8kLm2Pq9Xz") @PathVariable String assetId) {
        AssetService.CompleteResult result = assetService.complete(principal.publicId(), assetId);
        if (result.rejection() != null) {
            return ResponseEntity.status(result.rejection().getHttpStatus())
                    .body(Response.error(result.rejection(), result.asset()));
        }
        return ResponseEntity.ok(Response.ok(result.asset()));
    }

    @Operation(summary = "자산 조회", description = "VERIFIED 면 짧은 GET URL(기본 5분)이 실린다.")
    @ApiErrors("GEN-031: 없는 자산이거나 내 것이 아님")
    @GetMapping("/{assetId}")
    public Response<AssetResponse> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "자산 public id", example = "As8kLm2Pq9Xz") @PathVariable String assetId) {
        return Response.ok(assetService.get(principal.publicId(), assetId));
    }
}
