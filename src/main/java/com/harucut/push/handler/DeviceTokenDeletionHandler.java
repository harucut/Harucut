package com.harucut.push.handler;

import com.harucut.auth.service.UserDeletionHandler;
import com.harucut.push.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 탈퇴한 계정으로 푸시가 가지 않게 기기를 지운다
@Component
@RequiredArgsConstructor
public class DeviceTokenDeletionHandler implements UserDeletionHandler {

    private final DeviceTokenRepository deviceTokenRepository;

    @Transactional
    @Override
    public void handleUserDeletion(Long userId) {
        deviceTokenRepository.deleteByUserId(userId);
    }
}
