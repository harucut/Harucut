package com.harucut.participant.entity;

import com.harucut.participant.enums.ConsentDecision;
import com.harucut.participant.enums.ParticipantChannel;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 참가 동의 이력 — 덮어쓰지 않고 쌓는다. 현재 유효 상태는 항목별 마지막 결정으로 판정한다
@Entity
@Table(name = "participant_consent",
        indexes = @Index(name = "idx_participant_consent_participant_id", columnList = "participant_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_consent_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "capture_run_id")
    private Long captureRunId;

    @Column(name = "item_code", nullable = false, length = 64)
    private String itemCode;

    @Column(name = "item_version", nullable = false)
    private int itemVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantChannel channel;

    @Column(name = "experience_version_id", nullable = false)
    private Long experienceVersionId;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    public ParticipantConsent(Long participantId, Long eventId, String itemCode, int itemVersion,
                              ConsentDecision decision, ParticipantChannel channel, Long experienceVersionId,
                              LocalDateTime decidedAt) {
        this.participantId = participantId;
        this.eventId = eventId;
        this.itemCode = itemCode;
        this.itemVersion = itemVersion;
        this.decision = decision;
        this.channel = channel;
        this.experienceVersionId = experienceVersionId;
        this.decidedAt = decidedAt;
    }
}
