package com.harucut.draft.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 편집기 문서의 서버 초안. 서버는 문서를 해석하지 않고 합성에도 쓰지 않는다 — 두 기기 편집 충돌만 revision 으로 막는다
// (docs/adr-0006-p1-personal-editing.md §5)
@Entity
@Table(name = "frame_draft",
        indexes = {
                @Index(name = "idx_frame_draft_user_id", columnList = "user_id"),
                @Index(name = "idx_frame_draft_frame_id", columnList = "frame_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FrameDraft extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_draft_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // NULL = 새 프레임 초안
    @Column(name = "frame_id")
    private Long frameId;

    @Column(nullable = false, columnDefinition = "mediumtext")
    private String document;

    // 1부터. 저장할 때마다 조건부 UPDATE 로 1씩 오른다(FrameDraftRepository.updateIfRevision)
    @Column(nullable = false)
    private int revision;

    private FrameDraft(Long workspaceId, Long userId, Long frameId, String document) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.frameId = frameId;
        this.document = document;
        this.revision = 1;
    }

    public static FrameDraft create(Long workspaceId, Long userId, Long frameId, String document) {
        return new FrameDraft(workspaceId, userId, frameId, document);
    }
}
