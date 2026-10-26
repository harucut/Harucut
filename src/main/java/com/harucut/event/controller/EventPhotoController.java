package com.harucut.event.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.event.dto.PhotoDtos;
import com.harucut.event.service.PhotoService;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.result.dto.DownloadUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Tag(name = "행사 사진함")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/events/{eventId}/photos")
public class EventPhotoController {

    private final PhotoService photoService;

    @Operation(summary = "사진함",
            description = """
                    그 행사의 VIEW_PHOTOS 권한. 최신순 커서(limit 기본 30, 최대 100). 삭제 요청된 사진은 없다.
                    thumbnailUrl 은 5분 — 이미지가 깨지면 목록을 다시 받는다. 행사를 볼 수 없으면 404, 볼 수는 있지만 권한이 없으면 403 PHOTO-001.
                    """)
    @ApiErrors({"PHOTO-001: 사진 열람 권한 없음", "GEN-002: 잘못된 커서", "GEN-031: 없는 행사이거나 볼 수 없다"})
    @GetMapping
    public Response<PhotoDtos.PhotoPage> page(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Parameter(description = "행사 public id") @PathVariable String eventId,
                                              @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit,
                                              @Parameter(description = "이 시각 이후(포함)") @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                              @Parameter(description = "이 시각 이전(미포함)") @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                              @RequestParam(required = false) ParticipantChannel channel,
                                              @RequestParam(required = false) Long frameVersionId,
                                              @RequestParam(required = false) ComposeStatus status) {
        return Response.ok(photoService.page(principal.publicId(), eventId, cursor, limit, from, to, channel, frameVersionId, status));
    }

    @Operation(summary = "사진 한 장 다운로드", description = "DOWNLOAD_PHOTOS. 5분 URL, 파일 이름 「{행사명}-{photoId}.jpg」.")
    @ApiErrors({"PHOTO-001: 다운로드 권한 없음", "RESULT-003: 아직 준비 안 됨", "GEN-031: 없는 행사·사진이거나 볼 수 없다"})
    @PostMapping("/{photoId}/download")
    public Response<DownloadUrlResponse> download(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                  @Parameter(description = "사진 id") @PathVariable Long photoId) {
        return Response.ok(photoService.download(principal.publicId(), eventId, photoId));
    }

    @Operation(summary = "사진 삭제 요청",
            description = "DELETE_PHOTOS. 목록·묶음·참가자 결과 링크에서 즉시 빠진다(참가자에게는 만료로 보인다). 결과 파일은 커밋 후 지운다.")
    @ApiErrors({"PHOTO-001: 삭제 권한 없음", "GEN-031: 없는 행사·사진이거나 볼 수 없다"})
    @DeleteMapping("/{photoId}")
    public Response<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "행사 public id") @PathVariable String eventId,
                                 @Parameter(description = "사진 id") @PathVariable Long photoId) {
        photoService.delete(principal.publicId(), eventId, photoId);
        return Response.ok();
    }
}
