package com.harucut.text;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 참가자 자유 문구를 투명 PNG 로 굽는다(R5 D06). 미리보기와 최종 합성이 이 한 곳을 쓴다 —
 * 브라우저 canvas 의 줄바꿈·글꼴 메트릭을 서버 출력과 같다고 가정하지 않기 위해서다.
 *
 * <p>규칙: 줄은 사용자가 넣은 줄바꿈으로만 나눈다(자동 줄바꿈 없음 — 결과가 입력에서 바로 정해진다). 가운데 정렬.
 * 글자 크기는 칸 높이를 줄 수로 나눈 값(줄 높이 1.25)에서 시작해 가장 긴 줄이 칸 너비에 들어갈 때까지 1px 씩 줄인다.
 * 최소 크기({@value #MIN_FONT_SIZE}px)에서도 넘치면 DOES_NOT_FIT.
 */
@Component
public class TextRenderer {

    static final float LINE_HEIGHT = 1.25f;
    static final int MIN_FONT_SIZE = 12;
    static final int MAX_FONT_SIZE = 240;
    // 칸 가장자리에 글자가 닿지 않게 좌우 여백(너비의 2%씩)
    private static final double HORIZONTAL_FILL = 0.96;

    private final Map<TextStyle.Weight, Font> fonts = new EnumMap<>(TextStyle.Weight.class);

    public TextRenderer() {
        for (TextStyle.Weight weight : TextStyle.Weight.values()) {
            fonts.put(weight, load(weight.resource()));
        }
    }

    /** 검증을 통과한 문구 — 줄바꿈 정규화·앞뒤 공백 제거를 마친 값. 스냅샷·지문은 이 값으로 남긴다. */
    public record Normalized(String text, List<String> lines) {
    }

    public record Rendered(byte[] png, int width, int height, int fontSize) {
    }

    /**
     * 받은 문자열을 정규화하고 정책에 맞는지 본다 — 렌더 전에 따로 부를 수 있게 나눴다.
     *
     * @throws FreeTextException EMPTY·TOO_LONG·TOO_MANY_LINES·UNSUPPORTED_CHARACTER
     */
    public Normalized normalize(String raw, TextStyle style, int maxLength, int maxLines) {
        String text = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = Arrays.stream(text.split("\n", -1)).map(String::strip).toList();
        // 앞뒤의 빈 줄은 떼고, 가운데 빈 줄(의도한 줄 간격)은 남긴다
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isEmpty()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isEmpty()) {
            to--;
        }
        lines = lines.subList(from, to);
        if (lines.isEmpty()) {
            throw new FreeTextException(FreeTextRejection.EMPTY);
        }
        if (lines.size() > maxLines) {
            throw new FreeTextException(FreeTextRejection.TOO_MANY_LINES);
        }
        Font font = fonts.get(style.weight());
        int length = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                if (!displayable(codePoint) || !font.canDisplay(codePoint)) {
                    throw new FreeTextException(FreeTextRejection.UNSUPPORTED_CHARACTER);
                }
                length++;
                i += Character.charCount(codePoint);
            }
        }
        if (length > maxLength) {
            throw new FreeTextException(FreeTextRejection.TOO_LONG);
        }
        return new Normalized(String.join("\n", lines), List.copyOf(lines));
    }

    /** @throws FreeTextException DOES_NOT_FIT */
    public Rendered render(Normalized text, TextStyle style, int width, int height) {
        Font base = fonts.get(style.weight());
        FontRenderContext context = new FontRenderContext(null, true, true);
        int lines = text.lines().size();
        int size = Math.min(MAX_FONT_SIZE, (int) Math.floor(height / (lines * LINE_HEIGHT)));
        Font font = base.deriveFont((float) size);
        while (size >= MIN_FONT_SIZE && widest(font, text.lines(), context) > width * HORIZONTAL_FILL) {
            size--;
            font = base.deriveFont((float) size);
        }
        if (size < MIN_FONT_SIZE) {
            throw new FreeTextException(FreeTextRejection.DOES_NOT_FIT);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setFont(font);
            g.setColor(style.awtColor());

            float lineHeight = size * LINE_HEIGHT;
            float top = (height - lineHeight * lines) / 2f;
            for (int i = 0; i < lines; i++) {
                String line = text.lines().get(i);
                if (line.isEmpty()) {
                    continue;
                }
                LineMetrics metrics = font.getLineMetrics(line, context);
                float lineWidth = (float) font.getStringBounds(line, context).getWidth();
                float baseline = top + i * lineHeight + (lineHeight - (metrics.getAscent() + metrics.getDescent())) / 2f
                        + metrics.getAscent();
                g.drawString(line, (width - lineWidth) / 2f, baseline);
            }
        } finally {
            g.dispose();
        }
        return new Rendered(encode(image), width, height, size);
    }

    private static double widest(Font font, List<String> lines, FontRenderContext context) {
        return lines.stream().mapToDouble(line -> font.getStringBounds(line, context).getWidth()).max().orElse(0);
    }

    // 제어·서식·사용자 영역·미할당 문자는 받지 않는다(보이지 않는 글자로 칸을 속이거나 글꼴 밖 글리프가 □ 로 찍히지 않게)
    private static boolean displayable(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint)
                && type != Character.FORMAT && type != Character.PRIVATE_USE
                && type != Character.SURROGATE && type != Character.UNASSIGNED;
    }

    private static byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Font load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            // 글꼴이 없으면 기동에서 죽는다 — 다른 글꼴로 조용히 바꿔 미리보기·결과가 어긋나는 것보다 낫다
            throw new IllegalStateException("문구 렌더 글꼴을 읽을 수 없다: " + resource, e);
        }
    }
}
