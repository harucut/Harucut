package com.harucut.event.entity;

import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 행사 참가 경험의 불변 발행본 — 허용 레이아웃·프레임·동의 항목·편집 정책·테마를 한 번에 고정한다.
// 촬영 회차는 시작할 때 받은 버전으로 끝까지 간다 (ADR-0007 §3, R5 6절)
@Entity
@Table(name = "event_experience_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_experience_version_no", columnNames = {"event_id", "version_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventExperienceVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_experience_version_id")
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "settings_json", nullable = false, columnDefinition = "mediumtext")
    private String settingsJson;

    @Column(name = "published_by_user_id", nullable = false)
    private Long publishedByUserId;

    private EventExperienceVersion(Long eventId, int versionNo, String settingsJson, Long publishedByUserId) {
        this.eventId = eventId;
        this.versionNo = versionNo;
        this.settingsJson = settingsJson;
        this.publishedByUserId = publishedByUserId;
    }

    public static EventExperienceVersion publish(Long eventId, int versionNo, String settingsJson, Long publishedByUserId) {
        return new EventExperienceVersion(eventId, versionNo, settingsJson, publishedByUserId);
    }
}
