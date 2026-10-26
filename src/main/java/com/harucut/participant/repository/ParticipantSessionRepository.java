package com.harucut.participant.repository;

import com.harucut.participant.entity.ParticipantSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ParticipantSessionRepository extends JpaRepository<ParticipantSession, Long> {

    Optional<ParticipantSession> findByTokenHash(String tokenHash);

    // 부스 회차를 닫으면 그 손님의 세션을 끝낸다 — 공용 기기에 다음 손님이 이전 세션으로 들어가지 않게
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParticipantSession s set s.status = com.harucut.participant.enums.SessionStatus.REVOKED "
            + "where s.participantId = :participantId and s.status <> com.harucut.participant.enums.SessionStatus.REVOKED")
    int revokeAllByParticipantId(@Param("participantId") Long participantId);

    // 보관 종료 30일 뒤 — 그 행사 참가자의 세션 행
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ParticipantSession s where s.participantId in (select p.id from Participant p where p.eventId = :eventId)")
    int deleteByEventId(@Param("eventId") Long eventId);

    // 부스 기기 회수 — 그 기기로 만든 참가자 세션 전부
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ParticipantSession s set s.status = com.harucut.participant.enums.SessionStatus.REVOKED "
            + "where s.status <> com.harucut.participant.enums.SessionStatus.REVOKED "
            + "and s.participantId in (select p.id from Participant p where p.boothDeviceId = :boothDeviceId)")
    int revokeAllByBoothDeviceId(@Param("boothDeviceId") Long boothDeviceId);
}
