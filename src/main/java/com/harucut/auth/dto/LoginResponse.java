package com.harucut.auth.dto;

import com.harucut.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 결과. 토큰은 바디가 아니라 Set-Cookie 로 내려간다")
public record LoginResponse(

        @Schema(description = """
                로그인한 계정의 상태.
                `DELETED_REQUESTED` 면 **홈이 아니라 복구 안내 화면으로 보내야 한다** —
                이 상태에서는 일반 API 가 전부 403 이다.
                `BLOCKED` 면 **마이페이지로 보내야 한다** — 내 정보·로그아웃·탈퇴만 되고 그 밖의 API 는 403 `AUTH-042` 다.""",
                example = "ACTIVE")
        UserStatus userStatus
) {
}
