package com.harucut.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("자유 문구 렌더러")
class TextRendererTest {

    private static final TextRenderer RENDERER = new TextRenderer();

    @Test
    @DisplayName("정규화: NFC·줄바꿈 통일·앞뒤 공백과 빈 줄 제거, 가운데 빈 줄은 남긴다")
    void normalize() {
        TextRenderer.Normalized normalized = RENDERER.normalize("\n  민지 ♥ 준호 \r\n\r\n2026.10.27  \n\n",
                TextStyle.PRETENDARD_BOLD_DARK, 20, 3);

        assertThat(normalized.lines()).containsExactly("민지 ♥ 준호", "", "2026.10.27");
        assertThat(normalized.text()).isEqualTo("민지 ♥ 준호\n\n2026.10.27");
        // 앞뒤 정리는 JS trim 과 같은 범위 — NBSP·BOM·전각 공백도 뗀다(앱과 글자 수가 같게)
        assertThat(RENDERER.normalize("\u00A0\uFEFF\u3000하루\u202F", TextStyle.PRETENDARD_BOLD_DARK, 2, 1).text()).isEqualTo("하루");
    }

    @Test
    @DisplayName("거절: 빈 문구·줄 수 초과·글자 수 초과(줄바꿈 제외, 코드 포인트 기준)·이모지·제어 문자")
    void rejections() {
        assertReason(() -> RENDERER.normalize("  \n ", TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.EMPTY);
        assertReason(() -> RENDERER.normalize("a\nb", TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.TOO_MANY_LINES);
        assertReason(() -> RENDERER.normalize("가".repeat(21), TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.TOO_LONG);
        assertReason(() -> RENDERER.normalize("축하해 😀", TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.UNSUPPORTED_CHARACTER);
        assertReason(() -> RENDERER.normalize("a​b", TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.UNSUPPORTED_CHARACTER);
        assertReason(() -> RENDERER.normalize("a\tb", TextStyle.PRETENDARD_BOLD_DARK, 20, 1), FreeTextRejection.UNSUPPORTED_CHARACTER);
        // 20자 정확히는 통과
        assertThat(RENDERER.normalize("가".repeat(20), TextStyle.PRETENDARD_BOLD_DARK, 20, 1).lines()).hasSize(1);
    }

    @Test
    @DisplayName("칸 크기의 투명 PNG — 글자 픽셀은 스타일 색, 가장자리는 투명, 같은 입력은 같은 바이트")
    void rendersDeterministicPng() throws IOException {
        TextRenderer.Normalized text = RENDERER.normalize("민지 ♥ 준호", TextStyle.PRETENDARD_BOLD_LIGHT, 20, 1);

        TextRenderer.Rendered first = RENDERER.render(text, TextStyle.PRETENDARD_BOLD_LIGHT, 600, 120);
        TextRenderer.Rendered second = RENDERER.render(text, TextStyle.PRETENDARD_BOLD_LIGHT, 600, 120);

        assertThat(first.png()).isEqualTo(second.png());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first.png()));
        assertThat(image.getWidth()).isEqualTo(600);
        assertThat(image.getHeight()).isEqualTo(120);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getRGB(0, 0) >>> 24).isZero();
        boolean opaqueWhite = false;
        for (int x = 0; x < 600 && !opaqueWhite; x++) {
            for (int y = 0; y < 120; y++) {
                int argb = image.getRGB(x, y);
                if (argb >>> 24 == 0xFF && (argb & 0xFFFFFF) == 0xFFFFFF) {
                    opaqueWhite = true;
                    break;
                }
            }
        }
        assertThat(opaqueWhite).isTrue();
        assertThat(first.fontSize()).isBetween(TextRenderer.MIN_FONT_SIZE, 96);
    }

    @Test
    @DisplayName("긴 문구는 글자를 줄여 칸 너비에 맞추고, 최소 크기로도 넘치면 DOES_NOT_FIT")
    void shrinksOrRejects() {
        TextRenderer.Normalized shortText = RENDERER.normalize("하루", TextStyle.PRETENDARD_REGULAR_DARK, 30, 1);
        TextRenderer.Normalized longText = RENDERER.normalize("하루컷".repeat(10), TextStyle.PRETENDARD_REGULAR_DARK, 30, 1);

        int shortSize = RENDERER.render(shortText, TextStyle.PRETENDARD_REGULAR_DARK, 600, 120).fontSize();
        int longSize = RENDERER.render(longText, TextStyle.PRETENDARD_REGULAR_DARK, 600, 120).fontSize();
        assertThat(longSize).isLessThan(shortSize);

        assertReason(() -> RENDERER.render(longText, TextStyle.PRETENDARD_REGULAR_DARK, 120, 40), FreeTextRejection.DOES_NOT_FIT);
    }

    private static void assertReason(Runnable call, FreeTextRejection reason) {
        assertThatThrownBy(call::run).isInstanceOf(FreeTextException.class)
                .satisfies(e -> assertThat(((FreeTextException) e).reason()).isEqualTo(reason));
    }
}
