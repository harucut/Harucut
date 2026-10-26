package com.harucut.participant.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.participant.enums.ParticipantChannel;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 행사 참가 주체. User 와 별개다 — 개인 휴대폰은 같은 참가자로 결과를 모으고, 부스는 팀마다 새 참가자다
@Entity
@Table(name = "participant", indexes = @Index(name = "idx_participant_event_id", columnList = "event_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantChannel channel;

    @Column(name = "booth_device_id")
    private Long boothDeviceId;

    @Column(name = "linked_user_id")
    private Long linkedUserId;

    private Participant(Long eventId, ParticipantChannel channel, Long boothDeviceId) {
        this.publicId = PublicIds.generate();
        this.eventId = eventId;
        this.channel = channel;
        this.boothDeviceId = boothDeviceId;
    }

    public static Participant byod(Long eventId) {
        return new Participant(eventId, ParticipantChannel.BYOD, null);
    }

    public static Participant booth(Long eventId, Long boothDeviceId) {
        return new Participant(eventId, ParticipantChannel.BOOTH, boothDeviceId);
    }

    public boolean isBooth() {
        return channel == ParticipantChannel.BOOTH;
    }
}
