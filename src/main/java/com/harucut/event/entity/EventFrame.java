package com.harucut.event.entity;

import com.harucut.frame.enums.FrameType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 경험 발행본이 제공하는 프레임 발행본 — 참가자는 이 목록만 고른다. 빈 목록을 기본 카탈로그로 대체하지 않는다
@Entity
@Table(name = "event_frame",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_frame", columnNames = {"experience_version_id", "frame_version_id"}),
        indexes = @Index(name = "idx_event_frame_frame_version_id", columnList = "frame_version_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventFrame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_frame_id")
    private Long id;

    @Column(name = "experience_version_id", nullable = false)
    private Long experienceVersionId;

    @Column(name = "frame_version_id", nullable = false)
    private Long frameVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "layout_type", nullable = false, length = 32)
    private FrameType layoutType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    public EventFrame(Long experienceVersionId, Long frameVersionId, FrameType layoutType, int sortOrder, boolean isDefault) {
        this.experienceVersionId = experienceVersionId;
        this.frameVersionId = frameVersionId;
        this.layoutType = layoutType;
        this.sortOrder = sortOrder;
        this.isDefault = isDefault;
    }
}
