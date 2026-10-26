package com.harucut.admin.service;

import com.harucut.admin.dto.AdminSearchDtos;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 플랫폼 ADMIN 의 공간·행사 찾기 — 부분 일치, 최신순, 최대 20건 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminSearchService {

    static final int LIMIT = 20;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public List<AdminSearchDtos.WorkspaceItem> workspaces(String query) {
        List<Workspace> found = workspaceRepository.searchOrganizations(pattern(query), PageRequest.of(0, LIMIT));
        return found.stream().map(w -> new AdminSearchDtos.WorkspaceItem(w.getPublicId(), w.getName(), w.getType(),
                ownerEmails(w.getId()), offset(w.getCreatedAt()))).toList();
    }

    public List<AdminSearchDtos.EventItem> events(String query) {
        List<Event> found = eventRepository.search(pattern(query), PageRequest.of(0, LIMIT));
        Map<Long, Workspace> workspaces = workspaceRepository.findAllById(found.stream().map(Event::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Function.identity()));
        return found.stream().map(e -> {
            Workspace w = workspaces.get(e.getWorkspaceId());
            return new AdminSearchDtos.EventItem(e.getPublicId(), e.getSlug(), e.getName(), e.getStatus(),
                    w == null ? null : w.getPublicId(), w == null ? null : w.getName(), offset(e.getCreatedAt()));
        }).toList();
    }

    private List<String> ownerEmails(Long workspaceId) {
        List<Long> owners = membershipRepository.findAllByWorkspaceIdAndStatusOrderByIdAsc(workspaceId, MembershipStatus.ACTIVE).stream()
                .filter(m -> m.getRole() == WorkspaceRole.OWNER).map(WorkspaceMembership::getUserId).toList();
        return userRepository.findAllById(owners).stream().map(User::getEmail).filter(Objects::nonNull).sorted().toList();
    }

    // 소문자 부분 일치 패턴. LIKE 의 와일드카드는 글자로 다룬다 — 「%」 하나로 전체를 긁지 못하게.
    // 이스케이프 문자는 「!」 — 백슬래시는 MySQL 문자열 리터럴에서 다시 이스케이프돼 방언마다 다르게 깨진다
    private static String pattern(String query) {
        if (query == null || query.strip().length() < 2) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "검색어는 두 글자 이상이어야 합니다.");
        }
        String escaped = query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
