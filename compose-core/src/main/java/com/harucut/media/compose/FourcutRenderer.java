package com.harucut.media.compose;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.layout.FrameLayout;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// 스펙과 이미지 바이트를 받아 완성된 네컷을 만드는 순수 그리기 부품.
// 저장 포맷은 호출자가 정한다(ImageFormat) — 렌더러가 포맷을 고르면 서버가 만드는
// key 확장자와 어긋날 수 있고, 그 어긋남은 S3에 올라간 뒤에야 드러난다.
// S3를 모른다 — 이미지 가져오기(다운로드)와 결과 내보내기(업로드)는 호출자 몫이다.
// 그래서 픽셀 테스트가 S3 없이 돌고, 이 코드가 그대로 Lambda 함수 안으로 들어간다.
// 수식·순서·상수는 프론트 composeFrame.ts(drawFrameOnce)와 1:1이다 —
// 어긋나면 예외 없이 결과물만 편집 화면 미리보기와 틀어진다.
// 공유 모듈(compose-core) 소속이라 스프링 애노테이션이 없다 — 빈 등록은 앱의 ComposeConfig 몫
public class FourcutRenderer {

    // 프론트 DEFAULT_FRAME_BACKGROUND_COLOR — IMAGE 배경 아래에 깔리는 기본색
    private static final Color IMAGE_BACKGROUND_BASE = new Color(0x23, 0x26, 0x2D);

    // 썸네일 규격 — 긴 변 512, JPEG 품질 0.8 (사진 위주라 PNG 축소본은 여전히 크다)
    private static final int THUMBNAIL_LONG_EDGE = 512;
    private static final float THUMBNAIL_JPEG_QUALITY = 0.8f;

    // 원본 JPEG 품질. 썸네일과 다른 값인 것이 요점이다 — 원본은 사용자가 크게 보고 인화까지 한다.
    // 0.90인 근거는 실측 두 축이 같은 곳을 가리켰기 때문이다 (docs/perf-10k-dau.md §3.4·3.5):
    //   돈 — q0.85로 더 아끼는 건 월 $87로 전체 절감액의 2%뿐이다
    //   눈 — q0.85는 누끼 링·구운 텍스트에서 차이가 보이고, q0.95는 0.90과 구분이 안 된다
    // 크기 증가율도 q0.85→0.90이 +25%인데 q0.90→0.95는 +45%로 튄다.
    private static final float RESULT_JPEG_QUALITY = 0.90f;

    public RenderResult render(ComposeSpec spec, List<byte[]> sourcePhotos,
                               Map<String, byte[]> assets, ImageFormat format) {
        BufferedImage canvas = draw(spec, sourcePhotos, assets);
        return new RenderResult(encodeFull(canvas, format), encodeThumbnail(canvas));
    }

    // 기본값을 두지 않는다 — 호출부가 포맷을 말하게 강제한다.
    // 기본값이 있으면 "안 정한 것"과 "PNG로 정한 것"이 구분되지 않고,
    // 서버·Lambda 어느 한쪽이 포맷을 안 넘기는 실수가 조용히 통과한다
    private static byte[] encodeFull(BufferedImage canvas, ImageFormat format) {
        return switch (format) {
            case PNG -> encodePng(canvas);
            // JPEG는 알파를 못 다룬다 — ARGB 캔버스를 그대로 넣으면 색이 깨진다.
            // 실측 55ms(4000×6000)로 JPEG가 버는 시간의 2%라 이득 계산에 영향 없다
            case JPEG -> encodeJpeg(toRgb(canvas), RESULT_JPEG_QUALITY);
        };
    }

    // 그리기만 — 인코딩과 분리해 둔다. 같은 캔버스를 포맷 여러 벌로 인코딩해 비교하려면
    // (EncodingBenchmark) 캔버스를 손에 쥘 수 있어야 하고, 렌더와 인코딩의 시간 비중도
    // 나눠 놓지 않으면 못 잰다. 포맷이 선택 가능해지면 render가 이 위에 얹힌다
    BufferedImage draw(ComposeSpec spec, List<byte[]> sourcePhotos, Map<String, byte[]> assets) {
        if (sourcePhotos == null || sourcePhotos.size() != spec.slots().size()) {
            throw new IllegalArgumentException("원본 사진 수가 슬롯 수와 다르다");
        }

        BufferedImage canvas = new BufferedImage(
                spec.canvasWidth(), spec.canvasHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            applyQualityHints(g);
            drawBackground(g, spec, assets);
            drawSourcePhotos(g, spec, sourcePhotos);
            drawLayers(g, spec, assets);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    // ── 그리기 순서 1: 배경 (색 → 이미지) ──────────────────────

    private void drawBackground(Graphics2D g, ComposeSpec spec, Map<String, byte[]> assets) {
        Rectangle2D whole = new Rectangle2D.Double(0, 0, spec.canvasWidth(), spec.canvasHeight());
        switch (spec.background()) {
            case BackgroundAttributes.Color color -> {
                g.setColor(parseHexColor(color.value()));
                g.fill(whole);
            }
            case BackgroundAttributes.Image image -> {
                g.setColor(IMAGE_BACKGROUND_BASE);
                g.fill(whole);
                BufferedImage bg = crop(decode(requireAsset(assets, image.key()), ComposeFailureCode.ASSET_UNREADABLE), image.crop());
                Composite old = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, (float) clamp01(image.opacity())));
                // 배치 필드가 없으면 COVER·중앙(0.5)이라 예전 drawCover 와 같은 픽셀이 나온다
                drawPlaced(g, bg, whole, image.effectiveFit(), image.effectiveFocalX(), image.effectiveFocalY());
                g.setComposite(old);
            }
        }
    }

    // ── 그리기 순서 2: 원본 4장을 슬롯에 cover로 ──────────────────────

    private void drawSourcePhotos(Graphics2D g, ComposeSpec spec, List<byte[]> sourcePhotos) {
        List<FrameLayout.Slot> slots = spec.slots();
        for (int i = 0; i < slots.size(); i++) {
            FrameLayout.Slot slot = slots.get(i);
            Rectangle2D rect = new Rectangle2D.Double(slot.x(), slot.y(), slot.width(), slot.height());
            if (spec.cells() == null) {
                drawCover(g, decode(sourcePhotos.get(i), ComposeFailureCode.SOURCE_UNREADABLE), rect);
                continue;
            }
            // 자유 사진 칸(ADR-0012): 칸 중심 기준으로 회전한 좌표계에서, 모양으로 자른 칸에 그 칸의 순번 원본을 cover 로
            ComposeSpec.Cell cell = spec.cells().get(i);
            AffineTransform oldTransform = g.getTransform();
            g.rotate(Math.toRadians(cell.rotation()), rect.getCenterX(), rect.getCenterY());
            drawCover(g, decode(sourcePhotos.get(cell.shotIndex()), ComposeFailureCode.SOURCE_UNREADABLE), rect, cellShape(cell, rect));
            g.setTransform(oldTransform);
        }
    }

    private static Shape cellShape(ComposeSpec.Cell cell, Rectangle2D rect) {
        return switch (cell.shape()) {
            case ELLIPSE -> new Ellipse2D.Double(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
            // 반지름 r 인 둥근 모서리 — RoundRectangle2D 는 호의 지름을 받는다
            case RECT -> cell.cornerRadius() <= 0 ? rect : new RoundRectangle2D.Double(rect.getX(), rect.getY(),
                    rect.getWidth(), rect.getHeight(), cell.cornerRadius() * 2.0, cell.cornerRadius() * 2.0);
        };
    }

    // ── 그리기 순서 3: 레이어(스티커·구운 텍스트)를 zIndex 오름차순으로 ──────────────────────

    private void drawLayers(Graphics2D g, ComposeSpec spec, Map<String, byte[]> assets) {
        List<ComposeSpec.Layer> ordered = spec.layers().stream()
                .sorted(Comparator.comparingInt(ComposeSpec.Layer::zIndex))
                .toList();

        for (ComposeSpec.Layer layer : ordered) {
            BufferedImage image = decode(requireAsset(assets, layer.source()), ComposeFailureCode.ASSET_UNREADABLE);
            AffineTransform oldTransform = g.getTransform();
            Composite oldComposite = g.getComposite();

            // 프론트와 같은 변환: 중심으로 이동 → 회전 → 확대 → 좌상단으로 복귀
            g.translate(layer.x() + layer.width() / 2, layer.y() + layer.height() / 2);
            g.rotate(Math.toRadians(layer.rotation()));
            g.scale(layer.scale(), layer.scale());
            g.translate(-layer.width() / 2, -layer.height() / 2);
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, (float) clamp01(layer.opacity())));
            // 이미지를 width×height로 늘려 그린다 — drawImage(image, 0, 0, w, h)와 동일
            g.drawImage(image, 0, 0,
                    (int) Math.round(layer.width()), (int) Math.round(layer.height()), null);

            g.setComposite(oldComposite);
            g.setTransform(oldTransform);
        }
    }

    // 셀 누끼는 서버가 그리지 않는다 — 프론트가 배경을 제거하고 검은 배경까지 픽셀에
    // 구워서 업로드한다 (필터와 같은 계약). cellCutouts 플래그는 "프론트가 누끼를 딸 셀"
    // 이라는 표시로 스펙에 남지만, 렌더러는 읽지 않는다.
    // 이전의 가짜 비네트(방사형 그라디언트 + 녹색 링) 구현은 git 이력에 있다

    // ── 공통 도구 ──────────────────────

    // cover: 비율 유지 + 가운데 정렬 + 넘치는 부분은 사각형 밖으로 못 나가게 클리핑
    private void drawCover(Graphics2D g, BufferedImage image, Rectangle2D rect) {
        drawCover(g, image, rect, rect);
    }

    // rect 를 덮도록 맞추고 clip 모양으로 자른다(사각 칸은 clip == rect)
    private void drawCover(Graphics2D g, BufferedImage image, Rectangle2D rect, Shape clip) {
        Shape oldClip = g.getClip();
        g.clip(clip);
        double scale = Math.max(rect.getWidth() / image.getWidth(),
                rect.getHeight() / image.getHeight());
        double drawWidth = image.getWidth() * scale;
        double drawHeight = image.getHeight() * scale;
        AffineTransform transform = new AffineTransform();
        transform.translate(rect.getX() + (rect.getWidth() - drawWidth) / 2,
                rect.getY() + (rect.getHeight() - drawHeight) / 2);
        transform.scale(scale, scale);
        g.drawImage(image, transform, null);
        g.setClip(oldClip);
    }

    // 배경 배치. COVER 는 채우고 넘치는 쪽을, CONTAIN 은 전부 보이게 맞추고 남는 쪽을 focal 비율로 나눈다
    // (0 = 왼쪽/위로 붙임, 0.5 = 가운데, 1 = 오른쪽/아래로 붙임). 남는 영역은 이미 칠한 바탕색이 보인다
    private void drawPlaced(Graphics2D g, BufferedImage image, Rectangle2D rect,
                            BackgroundAttributes.Fit fit, double focalX, double focalY) {
        Shape oldClip = g.getClip();
        g.clip(rect);
        double scaleX = rect.getWidth() / image.getWidth();
        double scaleY = rect.getHeight() / image.getHeight();
        double scale = fit == BackgroundAttributes.Fit.CONTAIN ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);
        double drawWidth = image.getWidth() * scale;
        double drawHeight = image.getHeight() * scale;
        AffineTransform transform = new AffineTransform();
        transform.translate(rect.getX() + (rect.getWidth() - drawWidth) * focalX,
                rect.getY() + (rect.getHeight() - drawHeight) * focalY);
        transform.scale(scale, scale);
        g.drawImage(image, transform, null);
        g.setClip(oldClip);
    }

    // 정규화 crop 을 픽셀 영역으로 — 최소 1px 은 남긴다
    private static BufferedImage crop(BufferedImage image, BackgroundAttributes.Crop crop) {
        if (crop == null) {
            return image;
        }
        int x = (int) Math.floor(crop.x() * image.getWidth());
        int y = (int) Math.floor(crop.y() * image.getHeight());
        int width = Math.max(1, Math.min(image.getWidth() - x, (int) Math.round(crop.width() * image.getWidth())));
        int height = Math.max(1, Math.min(image.getHeight() - y, (int) Math.round(crop.height() * image.getHeight())));
        return image.getSubimage(Math.min(x, image.getWidth() - 1), Math.min(y, image.getHeight() - 1), width, height);
    }

    // #RGB 축약형은 #RRGGBB로 확장, 그 외 형식은 프론트 fallback과 같은 기본색
    private static Color parseHexColor(String value) {
        String hex = value.strip().replaceFirst("^#", "");
        if (hex.length() == 3 && hex.matches("[0-9a-fA-F]{3}")) {
            StringBuilder expanded = new StringBuilder(6);
            for (char c : hex.toCharArray()) {
                expanded.append(c).append(c);
            }
            hex = expanded.toString();
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            return IMAGE_BACKGROUND_BASE;
        }
        return new Color(Integer.parseInt(hex, 16));
    }

    // 읽지 못하면 무엇을 읽다 실패했는지(원본·자산) 종류를 달아 던진다 — 화면 문구가 갈린다
    private static BufferedImage decode(byte[] bytes, ComposeFailureCode unreadable) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ComposeFailureException(unreadable, "이미지 형식을 인식할 수 없다");
            }
            return image;
        } catch (IOException e) {
            throw new ComposeFailureException(unreadable, "이미지 바이트를 읽을 수 없다", e);
        }
    }

    private static byte[] requireAsset(Map<String, byte[]> assets, String source) {
        byte[] bytes = assets.get(source);
        if (bytes == null) {
            throw new ComposeFailureException(ComposeFailureCode.ASSET_MISSING, "스펙이 참조하는 이미지가 없다: " + source);
        }
        return bytes;
    }

    static byte[] encodePng(BufferedImage canvas) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(canvas, "png", out)) {
                throw new IllegalStateException("PNG 인코더를 찾을 수 없다");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("PNG 인코딩 실패", e);
        }
        return out.toByteArray();
    }

    // ── 썸네일 — 목록 그리드용 축소본 ──────────────────────

    private static byte[] encodeThumbnail(BufferedImage canvas) {
        return encodeJpeg(scaleToLongEdge(canvas, THUMBNAIL_LONG_EDGE), THUMBNAIL_JPEG_QUALITY);
    }

    // 절반씩 반복 축소 — 10배급 축소를 bilinear 한 번에 하면 보간이 인접 픽셀만 보고
    // 나머지를 버려서 가는 선이 자글자글해진다. 보간이 잘 듣는 배율(≤2배)만 쓰도록 나눠 줄인다.
    // 원본이 목표보다 작으면 확대하지 않는다 — 썸네일이 원본보다 클 이유가 없다
    private static BufferedImage scaleToLongEdge(BufferedImage source, int longEdge) {
        double ratio = Math.min(1.0,
                (double) longEdge / Math.max(source.getWidth(), source.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        BufferedImage current = source;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = drawScaled(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return drawScaled(current, targetWidth, targetHeight);
    }

    // 크기는 그대로 두고 알파만 버린다 — 원본을 JPEG로 인코딩하려면 먼저 거쳐야 하는 단계다.
    // 지금은 벤치(☐1-2)가 이 비용만 따로 재려고 쓴다. 배율 1의 drawScaled와 같은 일이라
    // 구현을 나누지 않는다 — 두 벌이 되면 한쪽만 고쳐지는 날이 온다
    static BufferedImage toRgb(BufferedImage source) {
        return drawScaled(source, source.getWidth(), source.getHeight());
    }

    // JPEG은 알파 채널을 못 다룬다(ARGB를 그대로 쓰면 예외나 색 왜곡) — RGB 캔버스에
    // 다시 그리며 알파를 버린다. 합성 결과는 배경이 항상 칠해져 있어 잃는 픽셀이 없다
    private static BufferedImage drawScaled(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            applyQualityHints(g);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    // 품질 지정은 ImageIO.write 기본 경로로는 안 된다 — writer의 압축 파라미터를 직접 만진다.
    // 품질을 상수로 읽지 않고 인자로 받는 이유: 썸네일과 원본이 같은 값을 쓸 이유가 없다.
    // 상수에 묶여 있으면 썸네일 품질을 건드릴 때 원본까지 따라 바뀐다
    static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG 인코더를 찾을 수 없다");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("JPEG 인코딩 실패", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static double clamp01(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
