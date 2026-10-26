package com.harucut.event.repository;

import com.harucut.event.entity.EventExperienceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventExperienceVersionRepository extends JpaRepository<EventExperienceVersion, Long> {

    @Query("select coalesce(max(v.versionNo), 0) from EventExperienceVersion v where v.eventId = :eventId")
    int findMaxVersionNo(Long eventId);
}
