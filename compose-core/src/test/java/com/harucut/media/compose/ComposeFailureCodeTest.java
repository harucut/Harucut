package com.harucut.media.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("합성 실패 종류 — 실패 통지에서 읽기")
class ComposeFailureCodeTest {

    @Test
    @DisplayName("코드 머리말이면 그 값, Lambda 시간 초과 문구면 TIMED_OUT, 그 밖·모르는 코드·빈 값은 UNKNOWN")
    void from() {
        assertThat(ComposeFailureCode.from("com.harucut.media.compose.ComposeFailureException",
                "[SOURCE_MISSING] 저장소에 없다: uploads/a.png")).isEqualTo(ComposeFailureCode.SOURCE_MISSING);
        assertThat(ComposeFailureCode.from(null, "2026-10-27T00:00:00Z 1234 Task timed out after 300.00 seconds"))
                .isEqualTo(ComposeFailureCode.TIMED_OUT);
        assertThat(ComposeFailureCode.from("java.lang.IllegalArgumentException", "이미지 형식을 인식할 수 없다"))
                .isEqualTo(ComposeFailureCode.UNKNOWN);
        assertThat(ComposeFailureCode.from(null, "[NOT_A_CODE] x")).isEqualTo(ComposeFailureCode.UNKNOWN);
        assertThat(ComposeFailureCode.from(null, null)).isEqualTo(ComposeFailureCode.UNKNOWN);
        assertThat(new ComposeFailureException(ComposeFailureCode.ASSET_MISSING, "없다").getMessage()).isEqualTo("[ASSET_MISSING] 없다");
    }
}
