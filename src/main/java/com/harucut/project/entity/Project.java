package com.harucut.project.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 조직 공간 아래의 선택 분류 — 행사를 묶고 브랜드 기본값을 준다(ADR-0009 §2). 프로젝트 없는 행사는 지금과 똑같다
@Entity
@Table(name = "project", indexes = @Index(name = "idx_project_workspace_id", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    // 행사 설정의 상속 가능한 필드 JSON. null 이면 이 단계의 값 없음
    @Column(name = "brand_defaults", columnDefinition = "mediumtext")
    private String brandDefaults;

    // 보관하면 새 행사를 지정할 수 없다(PROJECT-001). 기존 행사는 그대로
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    public Project(Long workspaceId, String name, String brandDefaults) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.name = name;
        this.brandDefaults = brandDefaults;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeBrandDefaults(String brandDefaults) {
        this.brandDefaults = brandDefaults;
    }

    public void archive(LocalDateTime now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void unarchive() {
        archivedAt = null;
    }
}
