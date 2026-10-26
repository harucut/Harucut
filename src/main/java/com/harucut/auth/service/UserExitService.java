package com.harucut.auth.service;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.oauth2.unlink.OAuth2UnlinkService;
import com.harucut.common.exception.BusinessException;
import com.harucut.storage.event.S3DeleteEvent;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserExitService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;
    private final List<UserDeletionHandler> handlers;
    private final List<OAuth2UnlinkService> unlinkServices;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceService workspaceService;

    @Transactional
    public void requestExit(String publicId) {
        User user = findUser(publicId);
        user.deleteRequested(LocalDateTime.now(clock));
        refreshTokenService.revoke(publicId);
    }

    @Transactional
    public void reActivate(String publicId) {
        User user = findUser(publicId);
        if (user.getUserStatus() != UserStatus.DELETED_REQUESTED) {
            throw new BusinessException(AuthErrorCode.NOT_DELETION_TARGET);
        }
        refreshTokenService.revoke(publicId);
        user.reActivate();
    }

    @Transactional
    public void exit(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));

        if (user.getUserStatus() != UserStatus.DELETED_REQUESTED) {
            throw new BusinessException(AuthErrorCode.NOT_DELETION_TARGET);
        }

        String publicId = user.getPublicId();
        String profileImageKey = user.getProfileImageUrl();

        handlers.forEach(handler -> handler.handleUserDeletion(userId));
        // 핸들러 뒤에 따로 부른다 — 프레임 등 핸들러가 지우는 데이터가 이 공간을 FK 로 참조한다.
        // 핸들러 목록의 순서는 보장되지 않아서 하나의 핸들러로 끼워 넣지 않았다
        workspaceService.deleteForUser(userId);

        // 핸들러들의 벌크 삭제(clearAutomatically)가 컨텍스트를 비웠다 — 위의 user는 유령이라
        // 그대로 delete()하면 익명화가 조용히 증발한다. 재로드가 필수다.
        User managedUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));

        // 익명화(delete)보다 먼저 — delete()가 providerId를 지우면 끊을 대상을 잃는다.
        // 실패는 예외로 올라가 이 사용자만 롤백·스킵되고 다음 날 배치가 재시도한다.
        unlinkServices.stream()
                .filter(service -> service.supports(managedUser.getProvider()))
                .findFirst()
                .ifPresent(service -> service.unlink(managedUser));

        refreshTokenService.revoke(publicId);
        managedUser.delete();

        if (S3Keys.isManagedKey(profileImageKey)) {
            eventPublisher.publishEvent(new S3DeleteEvent(List.of(profileImageKey)));
        }
    }

    private User findUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));
    }
}
