package com.harucut.auth.security;

import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리·사진 경로가 access 토큰에 실린 역할·상태를 믿지 않게 지금 값을 다시 본다(R5 L636).
 * 일반 API 는 요청마다 조회하는 비용을 피해 토큰 만료(30분)까지 그대로 둔다.
 */
@Service
@RequiredArgsConstructor
public class AccountStateGuard {

    private final UserRepository userRepository;

    /** 지금 쓸 수 없는 계정(없는 계정 포함)이거나, 관리자 경로인데 ADMIN 이 아니면 true */
    public boolean isDenied(String publicId, boolean adminRequired) {
        return userRepository.findStateByPublicId(publicId)
                .map(state -> state.getStatus() != UserStatus.ACTIVE
                        || (adminRequired && state.getRole() != UserRole.ROLE_ADMIN))
                .orElse(true);
    }
}
