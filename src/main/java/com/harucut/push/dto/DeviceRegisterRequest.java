package com.harucut.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRegisterRequest(
        @Schema(description = "Expo 푸시 토큰(getExpoPushTokenAsync 결과 그대로)", example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]")
        @NotBlank @Size(max = 255) @Pattern(regexp = DeviceTokenFormat.EXPO)
        String token,

        @Schema(description = "기기 플랫폼 — 소문자", allowableValues = {"ios", "android"})
        @NotBlank @Pattern(regexp = "ios|android")
        String platform
) {
}
