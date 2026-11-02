package com.harucut.auth.security;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * access 토큰에 실린 역할·상태를 믿지 않고 지금 값을 본다(R5 L636) — 관리·사진·행사 경로와 정지 토큰.
 * 일반 API 는 요청마다 조회하는 비용을 피해 토큰 만료(30분)까지 그대로 둔다.
 */
@Service
@RequiredArgsConstructor
public class AccountStateGuard {

    private final UserRepository userRepository;

    /** 지금 쓸 수 없으면 던진다 — 정지면 AUTH-042, 그 밖(탈퇴 요청·없는 계정·관리자 경로인데 ADMIN 아님)은 GEN-021 */
    public void requireUsable(String publicId, boolean adminRequired) {
        UserRepository.AccountState state = userRepository.findStateByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.FORBIDDEN));
        if (state.getStatus() == UserStatus.BLOCKED) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_BLOCKED);
        }
        if (state.getStatus() != UserStatus.ACTIVE || (adminRequired && state.getRole() != UserRole.ROLE_ADMIN)) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
    }

    /** 이 계정의 지금 역할·상태. 없는 계정이면 비어 있다 */
    public Optional<AuthenticatedUser> current(String publicId) {
        return userRepository.findStateByPublicId(publicId)
                .map(state -> new AuthenticatedUser(publicId, state.getRole(), state.getStatus()));
    }
}
