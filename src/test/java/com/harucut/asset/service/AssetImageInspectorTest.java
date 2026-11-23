package com.harucut.asset.service;

import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.storage.enums.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AssetImageInspector — 실제 형식·디코드 검증")
class AssetImageInspectorTest {

    private final AssetImageInspector inspector = new AssetImageInspector(40_000_000L);

    @Test
    @DisplayName("정상 PNG·JPEG·GIF 는 크기와 함께 통과한다")
    void validImagesPass() throws Exception {
        assertThat(inspector.inspect(image("png", 320, 200), ContentType.PNG))
                .isEqualTo(new AssetImageInspector.Inspection(320, 200));
        assertThat(inspector.inspect(image("jpg", 640, 480), ContentType.JPEG))
                .isEqualTo(new AssetImageInspector.Inspection(640, 480));
        assertThat(inspector.inspect(image("gif", 16, 16), ContentType.GIF))
                .isEqualTo(new AssetImageInspector.Inspection(16, 16));
    }

    @Test
    @DisplayName("큰 이미지도 서브샘플링으로 끝까지 읽는다")
    void largeImageDecodesWithSubsampling() throws Exception {
        assertThat(inspector.inspect(image("png", 4000, 3000), ContentType.PNG).width()).isEqualTo(4000);
    }

    @Test
    @DisplayName("선언과 실제 형식이 다르면 ASSET-003 — 확장자·헤더 선언만 믿지 않는다")
    void declaredTypeMismatch() throws Exception {
        assertRejected(image("png", 10, 10), ContentType.JPEG, AssetErrorCode.INVALID_IMAGE);
    }

    @Test
    @DisplayName("잘린 JPEG 은 ASSET-003 — 예외 없이 경고만 내는 경우도 실패로 본다")
    void truncatedJpeg() throws Exception {
        byte[] full = image("jpg", 400, 300);
        assertRejected(Arrays.copyOf(full, full.length / 2), ContentType.JPEG, AssetErrorCode.INVALID_IMAGE);
    }

    @Test
    @DisplayName("잘린 PNG 도 ASSET-003")
    void truncatedPng() throws Exception {
        byte[] full = image("png", 400, 300);
        assertRejected(Arrays.copyOf(full, full.length / 2), ContentType.PNG, AssetErrorCode.INVALID_IMAGE);
    }

    @Test
    @DisplayName("이미지가 아닌 바이트는 ASSET-003")
    void garbage() {
        assertRejected("hello, not an image".getBytes(), ContentType.PNG, AssetErrorCode.INVALID_IMAGE);
    }

    @Test
    @DisplayName("픽셀 상한을 넘으면 ASSET-004")
    void tooManyPixels() throws Exception {
        AssetImageInspector strict = new AssetImageInspector(10_000L);

        assertThatThrownBy(() -> strict.inspect(image("png", 200, 100), ContentType.PNG))
                .isInstanceOfSatisfying(AssetImageInspector.Rejected.class,
                        r -> assertThat(r.code()).isEqualTo(AssetErrorCode.TOO_MANY_PIXELS));
    }

    @Test
    @DisplayName("WEBP 는 형식을 알아보지만 읽을 리더가 없어 거부된다 — 합성기도 못 읽는 형식")
    void webpDetectedButRejected() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

        assertThat(AssetImageInspector.detect(webp)).isEqualTo(ContentType.WEBP);
        assertRejected(webp, ContentType.WEBP, AssetErrorCode.INVALID_IMAGE);
    }

    private void assertRejected(byte[] bytes, ContentType declared, AssetErrorCode expected) {
        assertThatThrownBy(() -> inspector.inspect(bytes, declared))
                .isInstanceOfSatisfying(AssetImageInspector.Rejected.class,
                        r -> assertThat(r.code()).isEqualTo(expected));
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        int type = format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y += 7) {
            for (int x = 0; x < width; x += 5) {
                image.setRGB(x, y, (x * 31 + y * 17) | 0xFF000000);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IllegalStateException("writer 없음: " + format);
        }
        return out.toByteArray();
    }
}
