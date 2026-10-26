package com.harucut.event.repository;

import com.harucut.event.entity.EventFrame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventFrameRepository extends JpaRepository<EventFrame, Long> {

    List<EventFrame> findAllByExperienceVersionIdOrderBySortOrderAsc(Long experienceVersionId);
}
