package com.harucut.auth.password;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public void changePassword(String publicId, String oldPassword, String newPassword) {
        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));

        if (user.getProvider() != Provider.HARUCUT) {
            throw new BusinessException(AuthErrorCode.SOCIAL_ACCOUNT_NO_PASSWORD);
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(AuthErrorCode.INCORRECT_PASSWORD);
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        // 비밀번호를 바꾼 이유가 탈취일 수 있다 — 이 기기를 포함해 모든 기기의 세션을 끊는다(재로그인)
        refreshTokenService.revokeAll(publicId);
    }
}
