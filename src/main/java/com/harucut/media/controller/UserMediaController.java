package com.harucut.media.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.PageResponse;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import com.harucut.media.dto.DisplayNameUpdateRequest;
import com.harucut.media.dto.MediaShareLinkResponse;
import com.harucut.media.dto.UserMediaResponse;
import com.harucut.media.service.UserMediaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사진 보관함")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/media")
public class UserMediaController {

    private final UserMediaService userMediaService;

    @Operation(
            summary = "내 사진 목록",
            description = """
                    합성이 끝난 사진들. 최신순 페이징이다.

                    **프레임과 달리 개수 제한이 없다.** 보관 기간만 적용된다
                    (BASIC 3일 · PLUS 3개월 · PRO 무제한). 그래서 BASIC 사용자도 3일 안에 찍은 건 전부 볼 수 있다 —
                    프레임을 하나도 저장 못 하는 것과 대조된다.

                    기간이 지난 사진은 목록에서 빠지고 `totalElements` 에도 안 잡힌다. 지워진 것은 아니다.
                    """)
    @ApiErrors({
            "GEN-002: page 가 0 미만이거나 size 가 1 미만",
            "GEN-031: 토큰은 유효한데 그 계정이 사라짐"
    })
    @GetMapping
    public Response<PageResponse<UserMediaResponse>> getMyMedia(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "페이지 번호 (0부터)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (1 이상)", example = "10") @RequestParam(defaultValue = "10") int size) {
        return Response.ok(userMediaService.getMyMedia(principal.publicId(), page, size));
    }

    @Operation(
            summary = "다운로드 URL",
            description = """
                    `data` 가 **객체가 아니라 URL 문자열 하나**다.

                    목록 응답의 `downloadUrl` 과 같은 성격이지만, 목록을 거치지 않고 필요할 때 새로 받는 용도다.
                    `Content-Disposition: attachment` 가 붙어 있어 브라우저가 바로 저장하고,
                    한글 파일명도 깨지지 않는다.
                    """)
    @ApiErrors({
            "SUBS-002: 요금제의 보관 기간이 지난 사진",
            "GEN-031: 없는 사진이거나 남의 사진 — 구분하지 않는다"
    })
    @GetMapping("/{mediaId}/download-url")
    public Response<String> getDownloadUrl(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Parameter(description = "미디어 ID", example = "1") @PathVariable Long mediaId) {
        return Response.ok(userMediaService.getDownloadUrl(principal.publicId(), mediaId));
    }

    @Operation(
            summary = "공유 링크",
            description = """
                    로그인 없는 사람에게 이 사진만 보여 주는 링크의 토큰. 받는 사람 주소는 `/s/<token>` 이고,
                    그 페이지는 `GET /api/public/media-shares` 에 `Authorization: Bearer <token>` 으로 사진을 받는다.

                    **사진마다 링크가 하나다.** 몇 번을 불러도 같은 토큰이라, 먼저 올린 글의 링크가 그대로 살고
                    다시 복사해도 같은 주소다. 처음 부를 때 만들어진다.

                    링크는 **이 사진이 내 기록에 보이는 동안** 열린다 — 지우거나 보관 기간이 지나면 닫힌다.
                    따로 만료일은 없고, 링크만 닫는 API 는 아직 없다(사진을 지우면 닫힌다).
                    """)
    @ApiErrors({
            "SUBS-002: 요금제의 보관 기간이 지난 사진",
            "GEN-031: 없는 사진이거나 남의 사진 — 구분하지 않는다"
    })
    @PostMapping("/{mediaId}/share-link")
    public Response<MediaShareLinkResponse> shareLink(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "미디어 ID", example = "1") @PathVariable Long mediaId) {
        return Response.ok(userMediaService.shareLink(principal.publicId(), mediaId));
    }

    @Operation(
            summary = "파일명 수정",
            description = """
                    다운로드할 때 저장될 이름을 바꾼다. 파일 자체는 그대로다.

                    **보낸 값이 그대로 저장되지 않는다.** 경로·따옴표·개행을 없애고 원본 확장자를 다시 붙인다.
                    `my_photo` 를 보내면 `my_photo.jpg` 가 된다(확장자는 원본 파일을 따른다 —
                    JPEG 전환 전에 만들어진 사진이면 `.png`). **응답의 `displayName` 을 화면에 반영할 것.**

                    (이렇게 꼼꼼히 정제하는 이유는 이 값이 다운로드 응답 헤더에 실리기 때문이다.
                    따옴표나 개행이 그대로 들어가면 헤더가 깨진다.)
                    """)
    @ApiErrors({
            "SUBS-002: 요금제의 보관 기간이 지난 사진",
            "GEN-031: 없는 사진이거나 남의 사진"
    })
    @PatchMapping("/{mediaId}/display-name")
    public Response<UserMediaResponse> updateDisplayName(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "미디어 ID", example = "1") @PathVariable Long mediaId,
            @RequestBody @Valid DisplayNameUpdateRequest request) {
        return Response.ok(userMediaService.updateDisplayName(
                principal.publicId(), mediaId, request.displayName()));
    }

    @Operation(
            summary = "사진 삭제",
            description = """
                    **기존 서버에 없던 API 다.** DB 행과 S3 파일(원본·썸네일)을 함께 지운다.
                    **되돌릴 수 없다.** 이 사진의 공유 링크도 함께 닫힌다.

                    조회·이름변경과 달리 **보관 기간을 보지 않는다.** 기간이 지나 목록에서 안 보이는 사진도
                    삭제는 된다 — 안 보인다고 정리조차 못 하면 곤란하기 때문이다.
                    """)
    @ApiErrors("GEN-031: 없는 사진이거나 남의 사진")
    @DeleteMapping("/{mediaId}")
    public Response<Void> deleteMedia(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Parameter(description = "미디어 ID", example = "1") @PathVariable Long mediaId) {
        userMediaService.deleteMedia(principal.publicId(), mediaId);
        return Response.ok();
    }
}
