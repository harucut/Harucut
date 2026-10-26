package com.harucut.frame.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.frame.enums.FrameType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 프레임의 불변 발행본 — 행사 제공본과 이후 합성의 디자인을 고정한다. 수정 API 가 없다 (ADR-0006 §4)
@Entity
@Table(name = "frame_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_frame_version_frame_no", columnNames = {"frame_id", "version_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FrameVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_version_id")
    private Long id;

    @Column(name = "frame_id", nullable = false)
    private Long frameId;

    // 프레임별 1부터
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "frame_type", nullable = false, length = 32)
    private FrameType frameType;

    // FrameSpec v2 JSON
    @Column(name = "spec_json", nullable = false, columnDefinition = "mediumtext")
    private String specJson;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    private FrameVersion(Long frameId, int versionNo, FrameType frameType, String specJson, Long createdByUserId) {
        this.frameId = frameId;
        this.versionNo = versionNo;
        this.frameType = frameType;
        this.specJson = specJson;
        this.createdByUserId = createdByUserId;
    }

    public static FrameVersion publish(Long frameId, int versionNo, FrameType frameType, String specJson,
                                       Long createdByUserId) {
        return new FrameVersion(frameId, versionNo, frameType, specJson, createdByUserId);
    }
}
