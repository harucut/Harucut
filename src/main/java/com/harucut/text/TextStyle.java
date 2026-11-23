package com.harucut.text;

import java.awt.Color;
import java.util.Arrays;
import java.util.Optional;

/**
 * 참가자 자유 문구의 서버 허용 스타일(R5 D06). 주최자는 이 중 일부를 행사에 고르고, 참가자는 id 만 보낸다.
 * 글꼴 파일은 서버 리소스의 Pretendard(OFL 1.1) — 미리보기와 최종 합성이 같은 파일·같은 렌더러를 쓴다.
 */
public enum TextStyle {
    PRETENDARD_REGULAR_DARK("pretendard-regular-dark", "보통 · 검정", Weight.REGULAR, "#111111"),
    PRETENDARD_REGULAR_LIGHT("pretendard-regular-light", "보통 · 흰색", Weight.REGULAR, "#FFFFFF"),
    PRETENDARD_BOLD_DARK("pretendard-bold-dark", "굵게 · 검정", Weight.BOLD, "#111111"),
    PRETENDARD_BOLD_LIGHT("pretendard-bold-light", "굵게 · 흰색", Weight.BOLD, "#FFFFFF");

    public enum Weight {
        REGULAR(400, "fonts/pretendard/Pretendard-Regular.otf"),
        BOLD(700, "fonts/pretendard/Pretendard-Bold.otf");

        private final int value;
        private final String resource;

        Weight(int value, String resource) {
            this.value = value;
            this.resource = resource;
        }

        public int value() {
            return value;
        }

        String resource() {
            return resource;
        }
    }

    private final String id;
    private final String label;
    private final Weight weight;
    private final String color;

    TextStyle(String id, String label, Weight weight, String color) {
        this.id = id;
        this.label = label;
        this.weight = weight;
        this.color = color;
    }

    public static Optional<TextStyle> byId(String id) {
        return Arrays.stream(values()).filter(style -> style.id.equals(id)).findFirst();
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Weight weight() {
        return weight;
    }

    public String color() {
        return color;
    }

    Color awtColor() {
        return Color.decode(color);
    }
}
