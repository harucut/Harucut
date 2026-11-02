package com.harucut.admin.service;

import com.harucut.admin.dto.AdminUserDtos;
import com.harucut.audit.service.AuditLogService;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 플랫폼 CMS 의 계정 찾기와 정지(R5 L399·L636).
 *
 * <p>「마지막 ADMIN 은 정지하지 못한다」는 따로 세지 않는다: 자기 자신을 정지하지 못하고 행위자가 잠금 아래에서 활성 ADMIN 이어야 하므로,
 * 정지가 성공하면 그 행위자가 ADMIN 으로 남는다. 두 관리자가 서로를 동시에 정지하면 줄을 서서 뒤에 온 쪽은 자기가 정지된 것을 보고 403 이다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AdminUserDtos.UserItem> find(String email) {
        return userRepository.findAllByEmailOrderByIdAsc(email).stream()
                .map(user -> new AdminUserDtos.UserItem(user.getPublicId(), user.getEmail(), user.getUsername(), user.getProvider(),
                        user.getUserRole(), user.getUserStatus(), offset(user)))
                .toList();
    }

    /**
     * 정지 — 모든 refresh 세션을 끊는다. 다시 로그인할 수 있지만 마이페이지와 탈퇴만 된다.
     * 살아 있는 옛 access 토큰은 일반 API 에서 만료까지 남고, 관리·사진 경로는 바로 막힌다. 이미 정지면 그대로 답한다
     */
    public AdminUserDtos.StatusResponse block(String adminPublicId, String userPublicId, String reason) {
        Locked locked = lock(adminPublicId, userPublicId);
        User target = locked.target();
        if (locked.actor() == target) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN, "자기 자신은 정지할 수 없습니다.");
        }
        // 탈퇴 요청 중인 계정은 유예 뒤 삭제된다 — 정지로 덮으면 삭제 배치가 건너뛰고, 해제하면 탈퇴 요청이 사라진다
        if (target.getUserStatus() == UserStatus.DELETED_REQUESTED) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_PENDING_DELETION);
        }
        if (target.getUserStatus() == UserStatus.ACTIVE) {
            target.block(LocalDateTime.now(clock));
            auditLogService.record(locked.actor().getId(), null, null, "USER_BLOCKED", "USER", target.getPublicId(), Map.of("reason", reason));
            refreshTokenService.revokeAll(target.getPublicId());
        }
        return new AdminUserDtos.StatusResponse(target.getPublicId(), target.getUserStatus());
    }

    /** 해제 — 정지 상태의 토큰도 다음 요청부터 풀린다(JwtAuthenticationFilter). 정지 상태가 아니면 그대로 답한다 */
    public AdminUserDtos.StatusResponse unblock(String adminPublicId, String userPublicId, String reason) {
        Locked locked = lock(adminPublicId, userPublicId);
        User target = locked.target();
        if (target.getUserStatus() == UserStatus.BLOCKED) {
            target.unblock();
            auditLogService.record(locked.actor().getId(), null, null, "USER_UNBLOCKED", "USER", target.getPublicId(), Map.of("reason", reason));
        }
        return new AdminUserDtos.StatusResponse(target.getPublicId(), target.getUserStatus());
    }

    // 늘 같은 순서로 잠근다 — 이 트랜잭션의 첫 읽기라 잠금 아래 값이다
    private Locked lock(String adminPublicId, String userPublicId) {
        Map<String, User> rows = new HashMap<>();
        Stream.of(adminPublicId, userPublicId).distinct().sorted()
                .forEach(publicId -> userRepository.lockByPublicId(publicId).ifPresent(user -> rows.put(publicId, user)));
        User actor = rows.get(adminPublicId);
        if (actor == null || actor.getUserStatus() != UserStatus.ACTIVE || actor.getUserRole() != UserRole.ROLE_ADMIN) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
        User target = rows.get(userPublicId);
        if (target == null || target.getUserStatus() == UserStatus.DELETED) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        return new Locked(actor, target);
    }

    private OffsetDateTime offset(User user) {
        return user.getCreatedAt() == null ? null : user.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime();
    }

    private record Locked(User actor, User target) {
    }
}
