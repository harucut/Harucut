package com.harucut.export.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.service.ExportService;
import com.harucut.result.dto.DownloadUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "행사 사진함")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/events/{eventId}/exports")
public class ExportController {

    private final ExportService exportService;

    @Operation(summary = "묶음 다운로드 접수",
            description = """
                    BULK_DOWNLOAD. 202 — 압축은 따로 돈다. photoIds(이 순서로) 또는 filter, 둘 다 없으면 완성 사진 전부.
                    이 행사의 완성(DONE)·미삭제 사진만 담고 나머지 id 는 뺀다. 1~2,000장.
                    """)
    @ApiErrors({"EXPORT-003: 비었거나 2,000장 초과", "PHOTO-001: 묶음 권한 없음", "GEN-031: 없는 행사이거나 볼 수 없다"})
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response<ExportDtos.Export> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Parameter(description = "행사 public id") @PathVariable String eventId,
                                              @RequestBody @Valid ExportDtos.ExportRequest request) {
        return Response.ok(exportService.create(principal.publicId(), eventId, request));
    }

    @Operation(summary = "내가 만든 최근 묶음", description = "최근 20개. 다른 사람의 묶음은 보이지 않는다.")
    @ApiErrors({"PHOTO-001: 묶음 권한 없음", "GEN-031: 없는 행사이거나 볼 수 없다"})
    @GetMapping
    public Response<List<ExportDtos.Export>> mine(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(exportService.mine(principal.publicId(), eventId));
    }

    @Operation(summary = "묶음 상태")
    @ApiErrors({"PHOTO-001: 묶음 권한 없음", "GEN-031: 없는 묶음이거나 내 것이 아니다"})
    @GetMapping("/{exportId}")
    public Response<ExportDtos.Export> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Parameter(description = "행사 public id") @PathVariable String eventId,
                                           @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(exportService.get(principal.publicId(), eventId, exportId));
    }

    @Operation(summary = "묶음 받기",
            description = """
                    5분 URL, 파일 이름 「{행사명}-{YYYYMMDD}.zip」(접수 날짜). 받을 때마다 권한·기한·담긴 사진을 다시 본다 —
                    기한이 지났거나 담긴 사진이 삭제됐으면 그 자리에서 무효로 하고 410 EXPORT-002.
                    """)
    @ApiErrors({"EXPORT-001: 아직 준비 안 됨", "EXPORT-002: 만료·실패·무효", "PHOTO-001: 묶음 권한 없음",
            "GEN-031: 없는 묶음이거나 내 것이 아니다"})
    @PostMapping("/{exportId}/download")
    public Response<DownloadUrlResponse> download(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                  @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(exportService.download(principal.publicId(), eventId, exportId));
    }
}
