package com.harucut.participant.repository;

import com.harucut.participant.entity.ParticipantSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantSessionRepository extends JpaRepository<ParticipantSession, Long> {

    Optional<ParticipantSession> findByTokenHash(String tokenHash);
}
