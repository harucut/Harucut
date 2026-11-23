package com.harucut.result.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ResultDownloadNameTest {

    private static final LocalDateTime TAKEN_AT = LocalDateTime.of(2026, 11, 2, 9, 5, 7);

    @Test
    @DisplayName("행사 이름-찍은 시각.확장자")
    void plain() {
        assertThat(ResultService.downloadName("민지 ♥ 준호", TAKEN_AT, "uploads/r/job-1.jpg"))
                .isEqualTo("민지 ♥ 준호-20261102-090507.jpg");
    }

    @Test
    @DisplayName("주최자가 넣은 제어·서식 문자는 뺀다 — 방향 바꾸기로 확장자를 속이지 못하게")
    void stripsInvisible() {
        assertThat(ResultService.downloadName("봄‮gpj.exe\u0007 사진", TAKEN_AT, "uploads/r/x.png"))
                .isEqualTo("봄gpj.exe 사진-20261102-090507.png");
    }

    @Test
    @DisplayName("40자로 자르되 글자 중간을 끊지 않는다")
    void cutsByCodePoint() {
        assertThat(ResultService.downloadName("😀".repeat(50), TAKEN_AT, "uploads/r/x.jpg"))
                .isEqualTo("😀".repeat(40) + "-20261102-090507.jpg");
    }

    @Test
    @DisplayName("이름에 남는 글자가 없으면 harucut")
    void emptyFallsBack() {
        assertThat(ResultService.downloadName("‮​", TAKEN_AT, "uploads/r/x.jpg"))
                .isEqualTo("harucut-20261102-090507.jpg");
    }
}
