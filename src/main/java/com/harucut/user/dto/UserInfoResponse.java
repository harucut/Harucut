package com.harucut.user.dto;

import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 정보")
public record UserInfoResponse(

        @Schema(description = "사용자 공개 ID (12자 문자열). **숫자 PK 가 아니다**", example = "AbCdEf12Gh")
        String id,

        @Schema(description = "이메일", example = "user@harucut.com")
        String email,

        @Schema(description = "닉네임", example = "하루컷")
        String username,

        @Schema(description = """
                프로필 이미지의 **presigned GET URL (24시간 유효)**. S3 key 가 아니다.
                오래 캐시해 두면 만료돼 깨진다 — 화면을 새로 열 때 다시 조회할 것.""",
                example = "https://harucut-bucket.s3.ap-northeast-2.amazonaws.com/uploads/users/AbCdEf12Gh/profile/550e8400.jpg?X-Amz-Signature=...")
        String profileUrl,

        @Schema(description = "가입 경로. `HARUCUT` 이 아니면 비밀번호가 없다 — 비밀번호 변경 메뉴를 감출 것",
                example = "HARUCUT", allowableValues = {"HARUCUT", "GOOGLE", "KAKAO", "NAVER", "APPLE"})
        String loginPlatform,

        @Schema(description = """
                **실제로 적용 중인** 요금제. 결제 주기가 지났는데 강등 배치가 아직 안 돈 공백기에는
                `BASIC` 으로 보인다 — 구독 조회 API 와 같은 기준이라 화면마다 다른 등급이 보이지 않는다.""",
                example = "BASIC", allowableValues = {"BASIC", "PLUS", "PRO"})
        String planTier,

        @Schema(description = "해당 요금제의 월 구독료(원). BASIC 0 / PLUS 3900 / PRO 9900", example = "0")
        int monthlyPrice,

        @Schema(description = """
                **지금** 계정 상태 — 토큰이 아니라 DB 값이다. `BLOCKED` 면 제한 화면을 그린다.
                정지 전에 받은 토큰을 든 사람도 여기서 바로 알 수 있다.""", example = "ACTIVE")
        UserStatus userStatus
) {
    public static UserInfoResponse from(User user, String profileUrl, String planTier, int monthlyPrice) {
        return new UserInfoResponse(
                user.getPublicId(),
                user.getEmail(),
                user.getUsername(),
                profileUrl,
                user.getProvider().name(),
                planTier,
                monthlyPrice,
                user.getUserStatus()
        );
    }
}
