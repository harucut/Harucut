package com.harucut.media.controller;

import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.media.dto.SharedMediaResponse;
import com.harucut.media.service.MediaSharePrincipal;
import com.harucut.media.service.MediaShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사진 공유 링크")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public/media-shares")
public class MediaShareController {

    private final MediaShareService mediaShareService;

    @Operation(summary = "공유한 사진 열기",
            description = """
                    받는 사람 페이지(`/s/<token>`)가 부른다. 로그인이 없다 —
                    `Authorization: Bearer <공유 토큰>` 으로만 받는다(경로·쿼리에 토큰을 싣지 않는다).
                    토큰은 `POST /api/auth/user/media/{mediaId}/share-link` 가 준다. 응답은 캐시되지 않고 리퍼러를 보내지 않는다.

                    링크는 **그 사진이 주인 기록에 보이는 동안만** 열린다. 사진을 지우거나, 요금제의 보관 기간이
                    지나거나(사진 목록과 같은 기준), 주인이 탈퇴(요청 포함)·정지되면 닫힌다. 따로 만료일은 없다.
                    열 때마다 다시 보므로 요금제를 올리거나 정지가 풀리면 같은 링크가 다시 열린다.

                    ⚠️ **닫힌 이유를 가르지 않는다.** 틀린 토큰·토큰 없음도 같은 404 `MEDIA-002` 다 —
                    링크가 있었는지 새지 않게. 429 `GEN-061` 만 다르다(같은 IP 의 틀린 토큰이 너무 잦다).

                    `imageUrl`·`downloadUrl` 은 몇 분만 열린다(기본 5분). 쓸 때마다 이 API 를 다시 부를 것.
                    """)
    @ApiErrors("MEDIA-002: 링크가 닫혔거나 없다 — 사진 삭제·보관 기간 지남·주인 탈퇴(요청)·정지·틀린 토큰·토큰 없음")
    @GetMapping
    public Response<SharedMediaResponse> open(@AuthenticationPrincipal MediaSharePrincipal principal) {
        return Response.ok(mediaShareService.open(principal));
    }
}
