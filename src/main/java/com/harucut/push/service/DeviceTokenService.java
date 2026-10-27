package com.harucut.push.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.push.dto.DeviceRegisterRequest;
import com.harucut.push.entity.DevicePlatform;
import com.harucut.push.entity.DeviceToken;
import com.harucut.push.repository.DeviceTokenRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    /** 계정당 기기 상한 — 넘으면 가장 오래 등록하지 않은 기기부터 지운다. */
    static final int MAX_DEVICES_PER_USER = 10;

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // 잠금 순서: 회원 행 → 기기 토큰 행. 같은 계정의 동시 등록은 회원 행에서 줄을 서서 상한을 넘지 않는다.
    // 다른 계정 것을 옮겨 오는 토큰 행은 그 계정의 회원 행을 잡지 않으므로 교차 대기가 없다
    @Transactional
    public void register(String publicId, DeviceRegisterRequest request) {
        User user = userRepository.lockByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        deviceTokenRepository.upsert(user.getId(), request.token(), DevicePlatform.from(request.platform()).name(), now);

        List<Long> ids = deviceTokenRepository.findIdsByUserIdNewestFirst(user.getId());
        if (ids.size() > MAX_DEVICES_PER_USER) {
            deviceTokenRepository.deleteByIds(ids.subList(MAX_DEVICES_PER_USER, ids.size()));
        }
        log.info("[푸시] 기기 등록 userId={} token={}", user.getId(), DeviceToken.mask(request.token()));
    }

    // 내 계정에 묶인 토큰만 지운다. 없어도 조용히 끝난다(로그아웃 직전에 불리므로 실패시키지 않는다)
    @Transactional
    public void unregister(String publicId, String token) {
        userRepository.findByPublicId(publicId)
                .ifPresent(user -> deviceTokenRepository.deleteByUserIdAndToken(user.getId(), token));
    }
}
