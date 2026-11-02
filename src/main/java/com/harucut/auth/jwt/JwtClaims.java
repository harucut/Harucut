package com.harucut.auth.jwt;

import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;

public record JwtClaims(
        String publicId,
        TokenType type,
        UserRole role,
        UserStatus status,
        // refresh 토큰의 기기 세션 — access 토큰은 null
        String sessionId
) {
}
