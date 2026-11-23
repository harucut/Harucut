package com.harucut.storage.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import com.harucut.storage.dto.PresignedUploadRequest;
import com.harucut.storage.dto.PresignedUploadResponse;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "파일 업로드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/files")
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(
            summary = "업로드용 URL 발급",
            description = """
                    **파일은 서버를 거치지 않는다.** 순서는 셋이다.

                    1. 이 API 로 `uploadUrl` 과 `key` 를 받는다
                    2. `uploadUrl` 로 **PUT** 한다 — 본문은 파일 바이트, 헤더 `Content-Type` 은 응답의 `contentType` 을 그대로
                    3. `key` 를 도메인 API 에 넘겨 등록한다 (프로필 변경 · 프레임 저장 · 합성 요청)

                    ⚠️ **3번을 안 하면 S3 에 파일만 남는 고아가 된다.** 정리해 주는 배치가 없다 —
                    단, `FOURCUT_SOURCE` 는 합성 요청에 쓰이지 않으면 **24시간 뒤 서버가 지운다**(합성이 접수되면 그 작업이 원본을 맡는다).

                    ⚠️ **`fileSize` 가 서명에 들어간다.** URL 을 받은 뒤 다른 파일을 올리면 크기가 달라져
                    S3 가 `SignatureDoesNotMatch` 403 으로 거부한다. 이건 서버 응답이 아니라 S3 응답이라
                    공통 봉투가 아니다.

                    `contentType` 은 요청과 응답의 의미가 다르다 — 보낼 때는 enum 이름(`PNG`),
                    받는 값은 실제 MIME(`image/png`)이고 PUT 헤더에는 후자를 쓴다.
                    """)
    @ApiErrors({
            "GEN-006: type 또는 contentType 이 enum 에 없는 값",
            "GEN-051: 확장자가 없거나 contentType 과 짝이 안 맞음 (`PNG` + `photo.jpg` 등)"
    })
    @PostMapping("/presigned-upload")
    public Response<PresignedUploadResponse> createPresignedUploadUrl(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody @Valid PresignedUploadRequest request
    ) {
        PresignedUploadResponse response = fileStorageService.generatePresignedUploadUrl(
                request.type(), request.filename(), request.contentType(),
                request.fileSize(), principal.publicId());

        return Response.ok(response);
    }

    @Operation(
            summary = "조회용 URL 발급",
            description = """
                    S3 key 로 24시간짜리 조회 URL 을 만든다.

                    **본인 경로(`uploads/users/{내 publicId}/...`)의 key 만 받는다.** 남의 key 는 403 이다.

                    화면에 이미지를 띄우는 용도라면 **보통 이 API 가 필요 없다** —
                    프로필·프레임·미디어 조회 응답에 이미 presigned URL 이 실려 온다.
                    방금 업로드한 파일을 등록 전에 미리보기하는 경우에만 쓰면 된다.
                    """)
    @ApiErrors({
            "GEN-004: key 파라미터가 없음",
            "GEN-021: 남의 경로 key 를 넘김"
    })
    @GetMapping("/presigned-img")
    public Response<String> getPresignedImgUrl(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "업로드 응답으로 받은 S3 key", required = true,
                    example = "uploads/users/AbCdEf12Gh/profile/550e8400-e29b-41d4-a716-446655440000.png")
            @RequestParam("key") String key
    ) {
        verifyOwnership(key, principal.publicId());

        return Response.ok(fileStorageService.generatePresignedGetUrl(key));
    }

    private void verifyOwnership(String key, String publicId) {
        if (!key.startsWith(S3Keys.userRoot(publicId))) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
    }
}
