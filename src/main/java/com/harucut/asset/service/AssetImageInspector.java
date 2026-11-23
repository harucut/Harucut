package com.harucut.asset.service;

import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.storage.enums.ContentType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

/**
 * 올라온 바이트가 선언한 형식의 "끝까지 읽히는" 이미지인지 본다. 헤더·확장자만으로 통과시키지 않는다(R5 16절).
 *
 * <p>원본 해상도로 풀지 않는다 — 6000px 이미지를 ARGB 로 풀면 수십~백 MB 다. 서브샘플링으로 약 1MP 만
 * 만들면서도 디코더는 스트림을 끝까지 읽으므로, 잘린 파일·깨진 압축은 여기서 드러난다.
 * 잘린 JPEG 은 예외 대신 경고만 내므로 경고도 실패로 본다.
 */
class AssetImageInspector {

    private static final long DECODE_TARGET_PIXELS = 1_000_000L;

    private final long maxPixels;

    AssetImageInspector(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    record Inspection(int width, int height) {
    }

    static final class Rejected extends Exception {
        private final AssetErrorCode code;

        Rejected(AssetErrorCode code, String detail) {
            super(detail, null, false, false);
            this.code = code;
        }

        AssetErrorCode code() {
            return code;
        }
    }

    Inspection inspect(byte[] bytes, ContentType declared) throws Rejected {
        ContentType detected = detect(bytes);
        if (declared == null || detected != declared) {
            throw new Rejected(AssetErrorCode.INVALID_IMAGE, "선언 " + declared + " / 실제 " + detected);
        }

        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = readerFor(in, declared);
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0) {
                    throw new Rejected(AssetErrorCode.INVALID_IMAGE, "크기 0");
                }
                if (pixels > maxPixels) {
                    throw new Rejected(AssetErrorCode.TOO_MANY_PIXELS, width + "x" + height);
                }

                WarningCollector warnings = new WarningCollector();
                reader.addIIOReadWarningListener(warnings);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = (int) Math.max(1, Math.ceil(Math.sqrt((double) pixels / DECODE_TARGET_PIXELS)));
                param.setSourceSubsampling(step, step, 0, 0);
                if (reader.read(0, param) == null || warnings.warning != null) {
                    throw new Rejected(AssetErrorCode.INVALID_IMAGE, "디코드 경고: " + warnings.warning);
                }
                return new Inspection(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new Rejected(AssetErrorCode.INVALID_IMAGE, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static ImageReader readerFor(ImageInputStream in, ContentType type) throws Rejected {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                if (type.getMimeType().endsWith(format) || (type == ContentType.JPEG && format.startsWith("jp"))) {
                    return reader;
                }
            } catch (IOException ignored) {
                // 다음 리더를 본다
            }
            reader.dispose();
        }
        throw new Rejected(AssetErrorCode.INVALID_IMAGE, "읽을 수 있는 리더 없음: " + type);
    }

    // 매직 넘버로 실제 형식을 본다. 모르는 형식이면 null
    static ContentType detect(byte[] bytes) {
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return ContentType.PNG;
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return ContentType.JPEG;
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return ContentType.GIF;
        }
        if (bytes.length >= 12 && startsWith(bytes, 'R', 'I', 'F', 'F')
                && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), new byte[]{'W', 'E', 'B', 'P'})) {
            return ContentType.WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static final class WarningCollector implements IIOReadWarningListener {
        private String warning;

        @Override
        public void warningOccurred(ImageReader source, String warning) {
            if (this.warning == null) {
                this.warning = warning;
            }
        }
    }
}
