package com.harucut.audit.service;

import com.harucut.audit.dto.AuditLogPage;
import com.harucut.audit.entity.AuditLog;
import com.harucut.audit.repository.AuditLogRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 운영 행동을 남긴다 (ADR-0008 §2-3). 호출자의 트랜잭션에 참여한다 — 행동이 롤백되면 기록도 남지 않는다.
 * 원문 토큰·개인 연락처는 detail 에 넣지 않는다(호출자 책임).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final int PAGE_SIZE = 50;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void record(Long actorUserId, Long workspaceId, Long eventId, String action,
                       String targetType, String targetId, Map<String, ?> detail) {
        String json = detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail);
        if (json != null && json.length() > 2000) {
            json = json.substring(0, 2000);
        }
        auditLogRepository.save(new AuditLog(LocalDateTime.now(clock), actorUserId, workspaceId, eventId, action,
                targetType, targetId, json));
    }

    @Transactional(readOnly = true)
    public AuditLogPage workspacePage(Long workspaceId, String cursor) {
        return page(auditLogRepository.findByWorkspaceIdAndIdLessThanOrderByIdDesc(workspaceId, before(cursor),
                PageRequest.of(0, PAGE_SIZE + 1)));
    }

    @Transactional(readOnly = true)
    public AuditLogPage platformPage(String cursor) {
        return page(auditLogRepository.findByIdLessThanOrderByIdDesc(before(cursor), PageRequest.of(0, PAGE_SIZE + 1)));
    }

    private AuditLogPage page(List<AuditLog> rows) {
        boolean more = rows.size() > PAGE_SIZE;
        List<AuditLog> shown = more ? rows.subList(0, PAGE_SIZE) : rows;
        Map<Long, String> names = userRepository.findAllById(shown.stream().map(AuditLog::getActorUserId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        Map<Long, String> events = eventRepository.findAllById(shown.stream().map(AuditLog::getEventId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Event::getId, Event::getPublicId));
        List<AuditLogPage.Item> items = shown.stream()
                .map(row -> new AuditLogPage.Item(row.getAction(),
                        row.getActorUserId() == null ? null : names.get(row.getActorUserId()),
                        row.getEventId() == null ? null : events.get(row.getEventId()),
                        row.getTargetType(), row.getTargetId(), row.getDetail(),
                        row.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime()))
                .toList();
        return new AuditLogPage(items, more ? Long.toString(shown.getLast().getId()) : null);
    }

    private static Long before(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "잘못된 커서");
        }
    }
}
