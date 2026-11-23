package com.harucut.media.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeInputFingerprintTest {

    private static final List<String> KEYS = List.of("a", "b", "c", "d");

    @Test
    @DisplayName("같은 입력이면 같고, spec·입력 순서·출력 계약 중 하나라도 다르면 달라진다")
    void changesWithAnyInput() {
        String base = ComposeInputFingerprint.of("{\"s\":1}", KEYS, 2);

        assertThat(base).hasSize(64).isEqualTo(ComposeInputFingerprint.of("{\"s\":1}", KEYS, 2));
        assertThat(ComposeInputFingerprint.of("{\"s\":2}", KEYS, 2)).isNotEqualTo(base);
        assertThat(ComposeInputFingerprint.of("{\"s\":1}", List.of("b", "a", "c", "d"), 2)).isNotEqualTo(base);
        assertThat(ComposeInputFingerprint.of("{\"s\":1}", KEYS, 1)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("key 경계가 흐려지지 않는다 — [ab,c] 와 [a,bc] 가 같은 값이 되지 않게")
    void keyBoundaries() {
        assertThat(ComposeInputFingerprint.of("x", List.of("ab", "c"), 2))
                .isNotEqualTo(ComposeInputFingerprint.of("x", List.of("a", "bc"), 2));
    }
}
