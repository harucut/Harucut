package com.harucut.audit.repository;

import com.harucut.audit.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // id 내림차순 커서 — afterId 보다 작은 것
    List<AuditLog> findByWorkspaceIdAndIdLessThanOrderByIdDesc(Long workspaceId, Long beforeId, Pageable pageable);

    List<AuditLog> findByIdLessThanOrderByIdDesc(Long beforeId, Pageable pageable);
}
