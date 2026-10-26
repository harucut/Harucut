package com.harucut.workspace.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workspace_membership",
        uniqueConstraints = @UniqueConstraint(name = "uk_workspace_membership",
                columnNames = {"workspace_id", "user_id"}),
        indexes = @Index(name = "idx_workspace_membership_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceMembership extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workspace_membership_id")
    private Long id;

    // 연관 대신 스칼라 id — 멤버십 조회는 항상 id 로 하고, 지연 로딩 경계를 만들지 않는다(terms_agreement 와 같은 선택)
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status;

    private WorkspaceMembership(Long workspaceId, Long userId, WorkspaceRole role) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
    }

    public static WorkspaceMembership owner(Long workspaceId, Long userId) {
        return new WorkspaceMembership(workspaceId, userId, WorkspaceRole.OWNER);
    }

    // 초대 수락이 쓰는 입구
    public static WorkspaceMembership member(Long workspaceId, Long userId, WorkspaceRole role) {
        return new WorkspaceMembership(workspaceId, userId, role);
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public void remove() {
        this.status = MembershipStatus.REMOVED;
    }

    // (workspace_id, user_id) 가 유니크라 내보냈던 사람을 다시 들일 때는 같은 행을 되살린다
    public void rejoin(WorkspaceRole role) {
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
    }
}
