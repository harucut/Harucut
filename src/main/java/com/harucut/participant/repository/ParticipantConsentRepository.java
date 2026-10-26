package com.harucut.participant.repository;

import com.harucut.participant.entity.ParticipantConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParticipantConsentRepository extends JpaRepository<ParticipantConsent, Long> {

    // 오래된 것부터 — 항목별 마지막 결정이 현재 상태다
    List<ParticipantConsent> findAllByParticipantIdOrderByIdAsc(Long participantId);
}
