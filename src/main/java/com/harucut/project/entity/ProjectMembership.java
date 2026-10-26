package com.harucut.project.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.event.converter.OperationPermissionsConverter;
import com.harucut.event.converter.PhotoPermissionsConverter;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

// 프로젝트 단위 위임 — 그 프로젝트의 모든 행사(앞으로 만들 행사 포함)에 같은 권한(ADR-0009 §3). 판정 때 행사 권한과 합친다
@Entity
@Table(name = "project_membership",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_membership", columnNames = {"project_id", "user_id"}),
        indexes = @Index(name = "idx_project_membership_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMembership extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_membership_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Convert(converter = OperationPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<OperationPermission> operations = EnumSet.noneOf(OperationPermission.class);

    @Convert(converter = PhotoPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<PhotoPermission> permissions = EnumSet.noneOf(PhotoPermission.class);

    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    public ProjectMembership(Long projectId, Long userId, Long grantedByUserId) {
        this.projectId = projectId;
        this.userId = userId;
        this.grantedByUserId = grantedByUserId;
    }

    public void replace(Set<OperationPermission> operations, Set<PhotoPermission> permissions) {
        this.operations = operations.isEmpty() ? EnumSet.noneOf(OperationPermission.class) : EnumSet.copyOf(operations);
        this.permissions = permissions.isEmpty() ? EnumSet.noneOf(PhotoPermission.class) : EnumSet.copyOf(permissions);
    }
}
