package com.harucut.participant.security;

import com.harucut.participant.enums.ParticipantChannel;

// X-Participant-Token 으로 인증된 참가자. 상태(RESTRICTED·ACTIVE)는 요청마다 다시 읽은 값이다
public record ParticipantPrincipal(
        Long sessionId,
        Long participantId,
        String participantPublicId,
        Long eventId,
        ParticipantChannel channel,
        Long boothDeviceId
) {
    public boolean isBooth() {
        return channel == ParticipantChannel.BOOTH;
    }
}
