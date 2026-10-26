package com.harucut.project.dto;

import com.harucut.event.dto.InheritableSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    @Schema(name = "ProjectCreateRequest")
    public record CreateRequest(
            @Schema(description = "프로젝트 이름", example = "2026 봄 웨딩 시즌") @NotBlank @Size(max = 100) String name,
            @Schema(description = "브랜드 기본값(선택) — 행사 설정의 상속 가능한 필드") InheritableSettings brandDefaults
    ) {
    }

    @Schema(name = "Project")
    public record Project(
            @Schema(description = "프로젝트 public id", example = "Pj3kL9mN2qRs") String projectId,
            @Schema(description = "공간 public id") String workspaceId,
            String name,
            @Schema(description = "브랜드 기본값 — 정한 필드만") InheritableSettings brandDefaults,
            @Schema(description = "보관 여부 — 보관하면 새 행사를 지정할 수 없다(PROJECT-001)") boolean archived,
            OffsetDateTime createdAt
    ) {
    }

    @Schema(name = "WorkspaceBrandDefaults")
    public record WorkspaceDefaults(
            @Schema(description = "공간 public id") String workspaceId,
            @Schema(description = "공간(기업) 단위 브랜드 기본값 — 정한 필드만") InheritableSettings brandDefaults
    ) {
    }
}
