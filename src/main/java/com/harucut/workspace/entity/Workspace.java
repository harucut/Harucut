package com.harucut.workspace.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.workspace.enums.WorkspaceKind;
import com.harucut.workspace.enums.WorkspaceType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 프레임·자산·초안(그리고 P2A 의 행사)이 속하는 공간. 권한은 이 행과 멤버십으로 판정한다 —
// S3 key 접두사는 정리 편의일 뿐 권한 근거가 아니다 (docs/adr-0006-p1-personal-editing.md §1)
@Entity
@Table(name = "workspace")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace extends BaseEntity {

    public static final String PERSONAL_NAME = "내 공간";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "workspace_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceType type;

    @Column(nullable = false, length = 100)
    private String name;

    // PERSONAL 일 때만 채운다 — 유니크라 "사용자당 개인 공간 하나"를 스키마가 보장한다
    @Column(name = "personal_owner_user_id", unique = true)
    private Long personalOwnerUserId;

    // 공간(기업) 단위 브랜드 기본값 — 행사 설정의 상속 가능한 필드 JSON(ADR-0009 §2). null 이면 이 단계의 값 없음
    @Column(name = "brand_defaults", columnDefinition = "mediumtext")
    private String brandDefaults;

    // 조직 공간의 종류(업체/주최자) — 사진을 누가 보는가(WorkspaceKind). 기본 HOST, 플랫폼 관리자가 바꾼다. 개인 공간은 행사가 없어 늘 HOST
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceKind kind = WorkspaceKind.HOST;

    private Workspace(WorkspaceType type, String name, Long personalOwnerUserId) {
        this.publicId = PublicIds.generate();
        this.type = type;
        this.name = name;
        this.personalOwnerUserId = personalOwnerUserId;
    }

    // 행사·기업이 쓰는 공유 공간. 만든 사람이 OWNER 멤버가 된다 — 소유자 컬럼은 PERSONAL 전용이라 비워 둔다
    public static Workspace organization(String name) {
        return new Workspace(WorkspaceType.ORGANIZATION, name, null);
    }

    public void changeBrandDefaults(String brandDefaults) {
        this.brandDefaults = brandDefaults;
    }

    public boolean isPersonal() {
        return type == WorkspaceType.PERSONAL;
    }

    public void changeKind(WorkspaceKind kind) {
        this.kind = kind;
    }

    /** 업체 공간 — 멤버는 이 공간 행사의 사진을 보지 않는다(EventAccessPolicy). 고객(EventGrant)만 본다 */
    public boolean isEnterprise() {
        return kind == WorkspaceKind.ENTERPRISE;
    }

    public static Workspace personal(Long ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("개인 공간에는 소유자가 필요하다");
        }
        return new Workspace(WorkspaceType.PERSONAL, PERSONAL_NAME, ownerUserId);
    }
}
