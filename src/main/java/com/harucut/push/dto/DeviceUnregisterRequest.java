package com.harucut.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 형식은 보지 않는다 — 없거나 틀린 토큰이어도 200(지울 것이 없을 뿐)
public record DeviceUnregisterRequest(@NotBlank @Size(max = 255) String token) {
}
