package com.harucut.admin.dto;

import com.harucut.user.enums.Provider;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

// 플랫폼 CMS 의 계정 찾기·정지
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    @Schema(name = "AdminUserItem", description = "계정 검색 결과 — 소셜 계정 때문에 같은 이메일이 여러 행일 수 있다")
    public record UserItem(@Schema(description = "사용자 public id") String userId, String email, String username,
                           Provider provider, UserRole userRole, UserStatus userStatus, OffsetDateTime createdAt) {
    }

    @Schema(name = "AdminUserStatusRequest", description = "정지·해제 — 사유는 감사에 남는다")
    public record StatusRequest(
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "약관 위반 신고") String reason) {
    }

    @Schema(name = "AdminUserStatusResponse")
    public record StatusResponse(@Schema(description = "사용자 public id") String userId, UserStatus userStatus) {
    }
}
