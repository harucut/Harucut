package com.harucut.project.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Tag(name = "프로젝트·브랜드 기본값")
@RestController
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "프로젝트 만들기",
            description = "조직 공간의 OWNER·ADMIN 만. 프로젝트는 선택 분류다 — 프로젝트 없는 행사는 지금과 같다. "
                    + "brandDefaults 는 행사 설정의 상속 가능한 필드(시간대·참가 채널·보관 일수·레이아웃·프레임·동의 항목·테마).")
    @ApiErrors({"GEN-002: 개인 공간이거나 이름·기본값이 잘못됐다", "GEN-031: 공간이 없거나 권한이 없다"})
    @PostMapping("/api/auth/workspaces/{workspaceId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ProjectDtos.Project> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                @RequestBody @Valid ProjectDtos.CreateRequest request) {
        return Response.ok(projectService.create(principal.publicId(), workspaceId, request));
    }

    @Operation(summary = "프로젝트 목록", description = "최신순. 보관한 것도 archived=true 로 함께 준다. OPERATOR 는 멤버인 프로젝트만 본다.")
    @ApiErrors({"GEN-002: 개인 공간", "GEN-031: 공간이 없거나 권한이 없다"})
    @GetMapping("/api/auth/workspaces/{workspaceId}/projects")
    public Response<List<ProjectDtos.Project>> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(projectService.list(principal.publicId(), workspaceId));
    }

    @Operation(summary = "프로젝트 수정",
            description = """
                    보낸 키만 바뀐다: `name`, `brandDefaults`(머지 패치 — 키마다 null 이면 그 기본값을 지움, 테마는 객체째 바꿈. 전체를 null 로 보내면 모두 지움),
                    `archived`(true 보관 — 새 행사 지정만 막고 기존 행사는 그대로, false 되살림).
                    기본값을 바꿔도 이미 게시한 행사는 바뀌지 않는다 — 다시 게시해야 들어간다.
                    """)
    @ApiErrors({"GEN-002: 이름·기본값이 잘못됐다", "GEN-031: 프로젝트가 없거나 권한이 없다"})
    @PatchMapping("/api/auth/projects/{projectId}")
    public Response<ProjectDtos.Project> patch(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                               @RequestBody JsonNode body) {
        return Response.ok(projectService.patch(principal.publicId(), projectId, body));
    }

    @Operation(summary = "프로젝트 멤버 목록", description = "공간 OWNER·ADMIN 만. 멤버마다 운영 권한(operations)과 사진 권한(permissions).")
    @ApiErrors({"GEN-031: 프로젝트가 없거나 멤버가 아니다", "GEN-021: OWNER·ADMIN 이 아니다"})
    @GetMapping("/api/auth/projects/{projectId}/members")
    public Response<List<ProjectDtos.Member>> members(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "프로젝트 public id") @PathVariable String projectId) {
        return Response.ok(projectService.members(principal.publicId(), projectId));
    }

    @Operation(summary = "프로젝트 멤버 권한 정하기",
            description = """
                    공간 OWNER·ADMIN 만. 대상은 이 공간의 OPERATOR 멤버. 그 프로젝트의 모든 행사(앞으로 만들 행사 포함)에 같은 권한을 준다 —
                    판정 때 행사 권한과 합친다. 권한을 통째로 바꾸고(없으면 만든다), 둘 다 비면 멤버에서 뺀다.
                    MANAGE_EVENT 를 받으면 그 프로젝트 안에서 행사를 만들 수 있다.
                    """)
    @ApiErrors({"GEN-031: 프로젝트·사용자가 없거나 멤버가 아니다", "GEN-021: OWNER·ADMIN 이 아니다", "GEN-002: 대상이 OPERATOR 멤버가 아니다"})
    @PutMapping("/api/auth/projects/{projectId}/members/{userId}")
    public Response<ProjectDtos.Member> putMember(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                                  @Parameter(description = "사용자 public id") @PathVariable String userId,
                                                  @RequestBody @Valid ProjectDtos.MemberRequest request) {
        return Response.ok(projectService.putMember(principal.publicId(), projectId, userId, request));
    }

    @Operation(summary = "프로젝트 멤버 빼기", description = "공간 OWNER·ADMIN 만. 그 프로젝트 행사의 상속 권한이 함께 사라진다(행사에서 직접 받은 권한은 남는다).")
    @ApiErrors({"GEN-031: 프로젝트·멤버가 없다", "GEN-021: OWNER·ADMIN 이 아니다"})
    @DeleteMapping("/api/auth/projects/{projectId}/members/{userId}")
    public Response<Void> deleteMember(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Parameter(description = "프로젝트 public id") @PathVariable String projectId,
                                       @Parameter(description = "사용자 public id") @PathVariable String userId) {
        projectService.deleteMember(principal.publicId(), projectId, userId);
        return Response.ok();
    }

    @Operation(summary = "공간 브랜드 기본값", description = "조직 공간의 OWNER·ADMIN 만. 정한 필드만 준다.")
    @ApiErrors({"GEN-002: 개인 공간", "GEN-031: 공간이 없거나 권한이 없다"})
    @GetMapping("/api/auth/workspaces/{workspaceId}/brand-defaults")
    public Response<ProjectDtos.WorkspaceDefaults> workspaceDefaults(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                     @Parameter(description = "공간 public id") @PathVariable String workspaceId) {
        return Response.ok(projectService.workspaceDefaults(principal.publicId(), workspaceId));
    }

    @Operation(summary = "공간 브랜드 기본값 수정",
            description = "본문이 곧 머지 패치 — 상속 가능한 필드만(다른 키는 400), null 이면 지운다, 테마는 객체째. 게시한 행사는 다시 게시해야 바뀐다.")
    @ApiErrors({"GEN-002: 개인 공간이거나 값이 잘못됐다", "GEN-031: 공간이 없거나 권한이 없다"})
    @PatchMapping("/api/auth/workspaces/{workspaceId}/brand-defaults")
    public Response<ProjectDtos.WorkspaceDefaults> patchWorkspaceDefaults(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                          @Parameter(description = "공간 public id") @PathVariable String workspaceId,
                                                                          @RequestBody JsonNode body) {
        return Response.ok(projectService.patchWorkspaceDefaults(principal.publicId(), workspaceId, body));
    }
}
