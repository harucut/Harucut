package com.harucut.participant.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.participant.enums.SessionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 참가자의 접속 자격. 세션 수명과 사진 보관 수명은 다르다 — 세션이 끝나도 결과 토큰은 보관 기한까지 유효하다
@Entity
@Table(name = "participant_session",
        indexes = @Index(name = "idx_participant_session_participant_id", columnList = "participant_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_session_id")
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private ParticipantSession(Long participantId, String tokenHash, LocalDateTime expiresAt) {
        this.participantId = participantId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.status = SessionStatus.RESTRICTED;
    }

    public static ParticipantSession restricted(Long participantId, String tokenHash, LocalDateTime expiresAt) {
        return new ParticipantSession(participantId, tokenHash, expiresAt);
    }

    public boolean usable(LocalDateTime now) {
        return status != SessionStatus.REVOKED && now.isBefore(expiresAt);
    }

    public void revoke() {
        this.status = SessionStatus.REVOKED;
    }
}
