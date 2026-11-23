package com.harucut.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "앱 로그인 코드 교환 — 시스템 브라우저에서 받은 일회용 코드와 셸이 만들어 둔 PKCE verifier")
public record AppLoginExchangeRequest(
        @NotBlank @Size(max = 128)
        @Schema(description = "harucut://oauth2/callback?code= 로 받은 일회용 코드(60초, 1회용)", requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        // RFC 7636 §4.1 — 43~128자, unreserved 문자
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._~-]{43,128}$")
        @Schema(description = "시작할 때 보낸 code_challenge 의 원래 값(PKCE code_verifier)", requiredMode = Schema.RequiredMode.REQUIRED)
        String codeVerifier
) {
}
