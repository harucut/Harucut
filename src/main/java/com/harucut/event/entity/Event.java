package com.harucut.event.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.event.enums.EventStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 행사 하나. 설정 초안은 settings_draft(JSON)에, 게시본은 event_experience_version 에 있다 (ADR-0007 §3)
@Entity
@Table(name = "event", indexes = @Index(name = "idx_event_workspace_id", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "capture_starts_at")
    private LocalDateTime captureStartsAt;

    @Column(name = "capture_ends_at")
    private LocalDateTime captureEndsAt;

    @Column(nullable = false, length = 40)
    private String timezone;

    @Column(name = "byod_enabled", nullable = false)
    private boolean byodEnabled;

    @Column(name = "booth_enabled", nullable = false)
    private boolean boothEnabled;

    @Column(name = "result_retention_days", nullable = false)
    private int resultRetentionDays;

    @Column(name = "settings_draft", nullable = false, columnDefinition = "mediumtext")
    private String settingsDraft;

    // 낙관적 잠금. PATCH 가 보낸 값과 다르면 409 EVENT-005. 게시·일시중지도 올린다
    @Version
    @Column(name = "settings_version", nullable = false)
    private int settingsVersion;

    @Column(name = "current_experience_version_id")
    private Long currentExperienceVersionId;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    // 선택 — 같은 공간의 프로젝트. 브랜드 기본값을 공간 → 프로젝트 → 행사 순서로 상속한다(ADR-0009 §2)
    @Column(name = "project_id")
    private Long projectId;

    private Event(Long workspaceId, String slug, String name, Long createdByUserId, String settingsDraft) {
        this.publicId = PublicIds.generate();
        this.workspaceId = workspaceId;
        this.slug = slug;
        this.name = name;
        this.createdByUserId = createdByUserId;
        this.settingsDraft = settingsDraft;
        this.status = EventStatus.DRAFT;
        this.timezone = "Asia/Seoul";
        this.byodEnabled = true;
        this.resultRetentionDays = 30;
    }

    public static Event draft(Long workspaceId, String slug, String name, Long createdByUserId, String settingsDraft) {
        return new Event(workspaceId, slug, name, createdByUserId, settingsDraft);
    }

    public void assignProject(Long projectId) {
        this.projectId = projectId;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeSlug(String slug) {
        if (currentExperienceVersionId != null) {
            throw new IllegalStateException("게시한 행사의 slug 는 바꿀 수 없다 — 배포한 링크·QR 이 깨진다");
        }
        this.slug = slug;
    }

    public void schedule(LocalDateTime captureStartsAt, LocalDateTime captureEndsAt) {
        this.captureStartsAt = captureStartsAt;
        this.captureEndsAt = captureEndsAt;
    }

    public void configure(String timezone, boolean byodEnabled, boolean boothEnabled, int resultRetentionDays) {
        this.timezone = timezone;
        this.byodEnabled = byodEnabled;
        this.boothEnabled = boothEnabled;
        this.resultRetentionDays = resultRetentionDays;
    }

    public void replaceSettingsDraft(String settingsDraft) {
        this.settingsDraft = settingsDraft;
    }

    // 게시: 현재 경험 포인터를 옮긴다. 처음이면 LIVE 가 되고, 촬영 종료 뒤 재게시는 상태를 바꾸지 않는다
    public void publish(Long experienceVersionId) {
        this.currentExperienceVersionId = experienceVersionId;
        if (status == EventStatus.DRAFT) {
            this.status = EventStatus.LIVE;
        }
    }

    public void pause() {
        requireLive("일시 중지");
        this.paused = true;
    }

    public void resume() {
        requireLive("재개");
        this.paused = false;
    }

    public void closeCapture() {
        requireLive("촬영 종료");
        this.status = EventStatus.CAPTURE_CLOSED;
    }

    // 보관 종료 — 촬영이 끝났고 보여 줄 결과가 남지 않았다(ADR-0008 §7). 공개 행사 응답은 404 가 된다
    public void archive(LocalDateTime now) {
        this.status = EventStatus.ARCHIVED;
        this.archivedAt = now;
    }

    // 새 참가·회차·합성을 받을 수 있는가. 이미 접수한 작업과 결과 수령은 이것과 무관하다
    public boolean captureOpen(LocalDateTime now) {
        return status == EventStatus.LIVE
                && !paused
                && (captureStartsAt == null || !now.isBefore(captureStartsAt))
                && (captureEndsAt == null || now.isBefore(captureEndsAt));
    }

    private void requireLive(String action) {
        if (status != EventStatus.LIVE) {
            throw new IllegalStateException(action + "는 운영 중(LIVE)인 행사만 할 수 있다: " + status);
        }
    }
}
