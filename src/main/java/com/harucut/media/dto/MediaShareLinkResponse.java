package com.harucut.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사진 공유 링크")
public record MediaShareLinkResponse(

        @Schema(description = """
                공유 토큰(base64url 43자). 받는 사람 주소는 `/s/<token>` 이다.
                **사진마다 하나라 다시 불러도 같은 값이다** — 먼저 올린 글의 링크가 그대로 산다.""",
                example = "q3V8Jt0bJ0mXr2cS9wN4YlKpA7eHfU1zDgQ6iT5oRkM")
        String token
) {
}
