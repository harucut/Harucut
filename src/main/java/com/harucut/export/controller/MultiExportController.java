package com.harucut.export.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.service.MultiExportService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 여러 행사 묶음 다운로드(ADR-0009 §6) — 행사 묶음(/api/auth/events/{eventId}/exports)과 같은 4개를 프로젝트·공간 범위로
@Tag(name = "행사 사진함")
@RestController
@RequiredArgsConstructor
public class MultiExportController {

    private static final String CREATE = """
            여러 행사 묶음 접수(202, 압축은 따로 돈다). eventIds 에 담을 행사를 명시한다 — 각 행사의 보이는 완성 사진 전부, 합계 1~2,000장.
            권한은 행사마다 본다: BULK_DOWNLOAD 사진 권한이 없는 행사가 있으면 403 PHOTO-001, 이용권 기능이 없으면 403 ENT-004 —
            둘 다 data.eventIds 에 빠진 행사. 응답은 행사 묶음과 같은 Export 에 scope·events 가 더해진다. ZIP 안은 {행사 slug}/0001-{photoId}.jpg.
            """;

    private final MultiExportService multiExportService;

    @Operation(summary = "프로젝트 묶음 다운로드 접수", description = "공간 OWNER·ADMIN 또는 프로젝트 멤버. " + CREATE)
    @ApiErrors({"EXPORT-003: 비었거나 2,000장 초과", "PHOTO-001: 묶음 권한 없는 행사가 있다", "ENT-004: 이용권에 묶음 기능이 없는 행사가 있다",
            "GEN-002: 이 프로젝트의 행사가 아니다", "GEN-031: 없는 프로젝트이거나 볼 수 없다"})
    @PostMapping("/api/auth/projects/{projectId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response<ExportDtos.Export> createProject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                                     @RequestBody @Valid ExportDtos.MultiExportRequest request) {
        return Response.ok(multiExportService.create(principal.publicId(), ExportScope.PROJECT, projectId, request));
    }

    @Operation(summary = "프로젝트 묶음 목록", description = "내가 만든 것 최신 20개(최신순). 공간 OWNER·ADMIN 또는 프로젝트 멤버.")
    @ApiErrors("GEN-031: 없는 프로젝트이거나 볼 수 없다")
    @GetMapping("/api/auth/projects/{projectId}/exports")
    public Response<List<ExportDtos.Export>> mineProject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @Parameter(description = "프로젝트 public id") @PathVariable String projectId) {
        return Response.ok(multiExportService.mine(principal.publicId(), ExportScope.PROJECT, projectId));
    }

    @Operation(summary = "프로젝트 묶음 상태")
    @ApiErrors("GEN-031: 없는 묶음·프로젝트이거나 볼 수 없다")
    @GetMapping("/api/auth/projects/{projectId}/exports/{exportId}")
    public Response<ExportDtos.Export> getProject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                                  @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(multiExportService.get(principal.publicId(), ExportScope.PROJECT, projectId, exportId));
    }

    @Operation(summary = "프로젝트 묶음 받기",
            description = "DONE 이고 기한 안이면 presigned URL. 받을 때 담긴 사진 삭제·행사마다 BULK_DOWNLOAD 를 다시 본다.")
    @ApiErrors({"EXPORT-001: 아직 준비되지 않았다", "EXPORT-002: 만료·무효", "PHOTO-001: 묶음 권한이 빠진 행사가 있다",
            "GEN-031: 없는 묶음·프로젝트이거나 볼 수 없다"})
    @PostMapping("/api/auth/projects/{projectId}/exports/{exportId}/download")
    public Response<DownloadUrlResponse> downloadProject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                                         @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(multiExportService.download(principal.publicId(), ExportScope.PROJECT, projectId, exportId));
    }

    @Operation(summary = "공간 묶음 다운로드 접수", description = "공간 OWNER·ADMIN. " + CREATE)
    @ApiErrors({"EXPORT-003: 비었거나 2,000장 초과", "PHOTO-001: 묶음 권한 없는 행사가 있다", "ENT-004: 이용권에 묶음 기능이 없는 행사가 있다",
            "GEN-002: 이 공간의 행사가 아니다", "GEN-021: OWNER·ADMIN 이 아니다", "GEN-031: 없는 공간이거나 멤버가 아니다"})
    @PostMapping("/api/auth/workspaces/{workspaceId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response<ExportDtos.Export> createWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                       @RequestBody @Valid ExportDtos.MultiExportRequest request) {
        return Response.ok(multiExportService.create(principal.publicId(), ExportScope.WORKSPACE, workspaceId, request));
    }

    @Operation(summary = "공간 묶음 목록", description = "내가 만든 것 최신 20개(최신순). 공간 OWNER·ADMIN.")
    @ApiErrors({"GEN-021: OWNER·ADMIN 이 아니다", "GEN-031: 없는 공간이거나 멤버가 아니다"})
    @GetMapping("/api/auth/workspaces/{workspaceId}/exports")
    public Response<List<ExportDtos.Export>> mineWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(multiExportService.mine(principal.publicId(), ExportScope.WORKSPACE, workspaceId));
    }

    @Operation(summary = "공간 묶음 상태")
    @ApiErrors({"GEN-021: OWNER·ADMIN 이 아니다", "GEN-031: 없는 묶음·공간이거나 멤버가 아니다"})
    @GetMapping("/api/auth/workspaces/{workspaceId}/exports/{exportId}")
    public Response<ExportDtos.Export> getWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                    @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(multiExportService.get(principal.publicId(), ExportScope.WORKSPACE, workspaceId, exportId));
    }

    @Operation(summary = "공간 묶음 받기",
            description = "DONE 이고 기한 안이면 presigned URL. 받을 때 담긴 사진 삭제·행사마다 BULK_DOWNLOAD 를 다시 본다.")
    @ApiErrors({"EXPORT-001: 아직 준비되지 않았다", "EXPORT-002: 만료·무효", "PHOTO-001: 묶음 권한이 빠진 행사가 있다",
            "GEN-021: OWNER·ADMIN 이 아니다", "GEN-031: 없는 묶음·공간이거나 멤버가 아니다"})
    @PostMapping("/api/auth/workspaces/{workspaceId}/exports/{exportId}/download")
    public Response<DownloadUrlResponse> downloadWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                           @Parameter(description = "묶음 public id") @PathVariable String exportId) {
        return Response.ok(multiExportService.download(principal.publicId(), ExportScope.WORKSPACE, workspaceId, exportId));
    }
}
