package com.harucut.media.compose;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.layout.FrameLayout.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 픽셀 테스트 — 작은 캔버스에 그려서 특정 좌표의 색으로 그리기 규칙을 고정한다.
// 경계(안티앨리어싱) 위가 아니라 영역 한가운데를 찍는다
@DisplayName("FourcutRenderer")
class FourcutRendererTest {

    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color RED = new Color(255, 0, 0);
    private static final Color GREEN = new Color(0, 255, 0);
    private static final Color BLUE = new Color(0, 0, 255);
    private static final Color YELLOW = new Color(255, 255, 0);

    private final FourcutRenderer renderer = new FourcutRenderer();

    @Nested
    @DisplayName("배경")
    class Background {

        @Test
        @DisplayName("COLOR 배경이 캔버스 크기 전체를 칠한다")
        void colorFillsCanvas() {
            BufferedImage result = decode(render(
                    spec(new BackgroundAttributes.Color("#FF0000"), List.of(), List.of(), List.of()),
                    List.of(), Map.of()));

            assertThat(result.getWidth()).isEqualTo(100);
            assertThat(result.getHeight()).isEqualTo(100);
            assertThat(pixel(result, 50, 50)).isEqualTo(RED);
        }

        @Test
        @DisplayName("#RGB 축약형은 #RRGGBB로 확장된다 — 프론트 normalizeHexColor와 동일")
        void shortHexExpanded() {
            BufferedImage result = decode(render(
                    spec(new BackgroundAttributes.Color("#0F0"), List.of(), List.of(), List.of()),
                    List.of(), Map.of()));

            assertThat(pixel(result, 50, 50)).isEqualTo(GREEN);
        }

        @Test
        @DisplayName("IMAGE 배경은 기본색 #23262D 위에 불투명도를 적용해 깔린다")
        void imageBackgroundBlendsOverBase() {
            ComposeSpec spec = spec(new BackgroundAttributes.Image("bg-key", 0.5, null),
                    List.of(), List.of(), List.of());

            BufferedImage result = decode(render(
                    spec, List.of(), Map.of("bg-key", solidPng(10, 10, RED))));

            // 빨강 50% + 기본색(35,38,45) 50%
            assertCloseTo(pixel(result, 50, 50), new Color(145, 19, 22), 3);
        }
    }

    /*
     * 배경 배치(fit·focal·crop). 필드가 없으면 예전과 같은 COVER·중앙이어야 한다 — 이미 저장된 프레임과
     * 이 필드를 모르는 Lambda 가 같은 결과를 내야 하기 때문이다.
     */
    @Nested
    @DisplayName("배경 배치 — fit·focal·crop")
    class BackgroundPlacement {

        private static final Color BASE = new Color(0x23, 0x26, 0x2D);

        @Test
        @DisplayName("배치 필드가 없으면 COVER·중앙을 명시한 것과 픽셀이 같다")
        void defaultsEqualExplicitCoverCenter() {
            Map<String, byte[]> assets = Map.of("bg", twoTonePng(40, 10, RED, BLUE));
            BufferedImage implicit = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null),
                    List.of(), List.of(), List.of()), List.of(), assets));
            BufferedImage explicit = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null, null,
                            BackgroundAttributes.Fit.COVER, 0.5, 0.5, null),
                    List.of(), List.of(), List.of()), List.of(), assets));

            for (int x = 0; x < 100; x += 9) {
                for (int y = 0; y < 100; y += 9) {
                    assertThat(pixel(implicit, x, y)).isEqualTo(pixel(explicit, x, y));
                }
            }
        }

        @Test
        @DisplayName("CONTAIN 은 이미지 전체를 넣고 남는 쪽에 바탕색이 보인다")
        void containLetterboxes() {
            // 40x10 → 100x25 로 맞춰 세로 가운데. 위·아래는 바탕색
            BufferedImage result = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null, null,
                            BackgroundAttributes.Fit.CONTAIN, null, null, null),
                    List.of(), List.of(), List.of()), List.of(), Map.of("bg", solidPng(40, 10, RED))));

            assertThat(pixel(result, 50, 5)).isEqualTo(BASE);
            assertThat(pixel(result, 50, 50)).isEqualTo(RED);
            assertThat(pixel(result, 50, 95)).isEqualTo(BASE);
        }

        @Test
        @DisplayName("COVER 의 focalX 0 은 왼쪽을, 1 은 오른쪽을 남긴다")
        void focalChoosesVisibleSide() {
            Map<String, byte[]> assets = Map.of("bg", twoTonePng(40, 10, RED, BLUE));

            BufferedImage left = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null, null,
                            null, 0.0, null, null),
                    List.of(), List.of(), List.of()), List.of(), assets));
            BufferedImage right = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null, null,
                            null, 1.0, null, null),
                    List.of(), List.of(), List.of()), List.of(), assets));

            assertThat(pixel(left, 50, 50)).isEqualTo(RED);
            assertThat(pixel(right, 50, 50)).isEqualTo(BLUE);
        }

        @Test
        @DisplayName("crop 으로 원본의 왼쪽 절반만 쓰면 캔버스 전체가 왼쪽 색이다")
        void cropUsesSourceRegion() {
            BufferedImage result = decode(render(spec(new BackgroundAttributes.Image("bg", 1.0, null, null,
                            null, null, null, new BackgroundAttributes.Crop(0, 0, 0.5, 1)),
                    List.of(), List.of(), List.of()), List.of(), Map.of("bg", twoTonePng(20, 20, RED, BLUE))));

            assertThat(pixel(result, 5, 50)).isEqualTo(RED);
            assertThat(pixel(result, 95, 50)).isEqualTo(RED);
        }

        @Test
        @DisplayName("범위를 벗어난 focal·crop 은 만들 수 없다")
        void invalidPlacementRejected() {
            assertThatThrownBy(() -> new BackgroundAttributes.Image("bg", 1.0, null, null, null, 1.5, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BackgroundAttributes.Crop(0.6, 0, 0.5, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("원본 4장 — 슬롯에 cover")
    class SourcePhotos {

        @Test
        @DisplayName("원본이 슬롯 순서대로 각자의 칸에 그려진다")
        void photosLandInTheirSlots() {
            ComposeSpec spec = spec(whiteBackground(),
                    List.of(new Slot(5, 5, 40, 40), new Slot(55, 5, 40, 40),
                            new Slot(5, 55, 40, 40), new Slot(55, 55, 40, 40)),
                    List.of(), List.of());

            BufferedImage result = decode(render(spec, List.of(
                    solidPng(10, 10, RED), solidPng(10, 10, GREEN),
                    solidPng(10, 10, BLUE), solidPng(10, 10, YELLOW)), Map.of()));

            assertThat(pixel(result, 25, 25)).isEqualTo(RED);
            assertThat(pixel(result, 75, 25)).isEqualTo(GREEN);
            assertThat(pixel(result, 25, 75)).isEqualTo(BLUE);
            assertThat(pixel(result, 75, 75)).isEqualTo(YELLOW);
        }

        @Test
        @DisplayName("cover에서 넘치는 부분은 슬롯 밖으로 나가지 않는다 — 클리핑")
        void overflowClippedToSlot() {
            // 40x10 가로 이미지를 20x20 슬롯에 → 확대 후 좌우가 슬롯 밖으로 넘친다
            ComposeSpec spec = spec(whiteBackground(),
                    List.of(new Slot(40, 40, 20, 20)), List.of(), List.of());

            BufferedImage result = decode(render(
                    spec, List.of(solidPng(40, 10, BLUE)), Map.of()));

            assertThat(pixel(result, 50, 50)).isEqualTo(BLUE);
            assertThat(pixel(result, 30, 50)).isEqualTo(WHITE);
            assertThat(pixel(result, 70, 50)).isEqualTo(WHITE);
        }

        @Test
        @DisplayName("cover는 가운데 정렬이다 — 잘리는 건 양쪽 끝이다")
        void coverIsCenterAligned() {
            // 왼쪽 절반 빨강, 오른쪽 절반 파랑인 20x10 이미지 → 정사각 슬롯 중앙에 색 경계가 온다
            ComposeSpec spec = spec(whiteBackground(),
                    List.of(new Slot(40, 40, 20, 20)), List.of(), List.of());

            BufferedImage result = decode(render(
                    spec, List.of(twoTonePng(20, 10, RED, BLUE)), Map.of()));

            assertCloseTo(pixel(result, 44, 50), RED, 3);
            assertCloseTo(pixel(result, 56, 50), BLUE, 3);
        }
    }

    @Nested
    @DisplayName("자유 사진 칸 — 회전·모양·입력 순번(ADR-0012)")
    class FreeCells {

        private final List<Slot> grid = List.of(new Slot(5, 5, 40, 40), new Slot(55, 5, 40, 40),
                new Slot(5, 55, 40, 40), new Slot(55, 55, 40, 40));
        private final List<byte[]> photos = List.of(solidPng(10, 10, RED), solidPng(10, 10, GREEN),
                solidPng(10, 10, BLUE), solidPng(10, 10, YELLOW));

        @Test
        @DisplayName("칸 i 에는 원본 shotIndex 번이 들어간다 — 거꾸로 매기면 거꾸로")
        void shotIndexChoosesSource() {
            ComposeSpec spec = cells(grid, List.of(rect(0, 0, 3), rect(0, 0, 2), rect(0, 0, 1), rect(0, 0, 0)));

            BufferedImage result = decode(render(spec, photos, Map.of()));

            assertThat(pixel(result, 25, 25)).isEqualTo(YELLOW);
            assertThat(pixel(result, 75, 25)).isEqualTo(BLUE);
            assertThat(pixel(result, 25, 75)).isEqualTo(GREEN);
            assertThat(pixel(result, 75, 75)).isEqualTo(RED);
        }

        @Test
        @DisplayName("ELLIPSE 는 칸에 내접한 타원 밖(모서리)을 배경으로 남기고, RECT 둥근 모서리도 모서리를 남긴다")
        void shapesClip() {
            ComposeSpec spec = cells(grid, List.of(
                    new ComposeSpec.Cell(0, ComposeSpec.CellShape.ELLIPSE, 0, 0), rect(0, 15, 1), rect(0, 0, 2), rect(0, 0, 3)));

            BufferedImage result = decode(render(spec, photos, Map.of()));

            assertThat(pixel(result, 25, 25)).isEqualTo(RED);     // 타원 중심
            assertThat(pixel(result, 7, 7)).isEqualTo(WHITE);     // 타원 밖 모서리
            assertThat(pixel(result, 75, 25)).isEqualTo(GREEN);
            assertThat(pixel(result, 57, 7)).isEqualTo(WHITE);    // 둥근 모서리 밖
            assertThat(pixel(result, 7, 75)).isEqualTo(BLUE);     // 반지름 0 은 모서리까지
        }

        @Test
        @DisplayName("회전은 칸 중심 기준 — 90도 돌린 가로 칸은 세로로 서서, 원래 칸 밖을 덮고 원래 칸의 양 끝을 비운다")
        void rotatesAroundCenter() {
            // 60×20 가로 칸, 중심 (50,50) — 90도 돌리면 20×60 세로
            List<Slot> one = List.of(new Slot(20, 40, 60, 20));
            ComposeSpec spec = new ComposeSpec(100, 100, whiteBackground(), one, List.of(false), List.of(),
                    List.of(rect(90, 0, 0)));

            BufferedImage result = decode(render(spec, List.of(solidPng(10, 10, RED)), Map.of()));

            assertThat(pixel(result, 50, 50)).isEqualTo(RED);
            assertThat(pixel(result, 50, 25)).isEqualTo(RED);     // 원래 칸 위쪽 밖 — 회전한 칸 안
            assertThat(pixel(result, 25, 50)).isEqualTo(WHITE);   // 원래 칸 왼쪽 끝 — 회전한 칸 밖
        }

        @Test
        @DisplayName("칸 정보가 슬롯 수와 다르거나 shotIndex 가 겹치면 스냅샷을 만들 수 없다")
        void invalidCells() {
            assertThatThrownBy(() -> cells(grid, List.of(rect(0, 0, 0))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cells(grid, List.of(rect(0, 0, 0), rect(0, 0, 0), rect(0, 0, 1), rect(0, 0, 2))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private ComposeSpec cells(List<Slot> slots, List<ComposeSpec.Cell> cells) {
            return new ComposeSpec(100, 100, whiteBackground(), slots, List.of(false, false, false, false), List.of(), cells);
        }

        private static ComposeSpec.Cell rect(double rotation, int cornerRadius, int shotIndex) {
            return new ComposeSpec.Cell(rotation, ComposeSpec.CellShape.RECT, cornerRadius, shotIndex);
        }
    }

    @Nested
    @DisplayName("실패 종류 — 원본·자산을 구분한다")
    class FailureCodes {

        @Test
        @DisplayName("원본을 읽지 못하면 SOURCE_UNREADABLE, 자산이 없으면 ASSET_MISSING, 자산을 읽지 못하면 ASSET_UNREADABLE")
        void codes() {
            ComposeSpec onePhoto = spec(whiteBackground(), List.of(new Slot(0, 0, 10, 10)), List.of(), List.of());
            assertThatThrownBy(() -> render(onePhoto, List.of(new byte[]{1, 2, 3}), Map.of()))
                    .isInstanceOfSatisfying(ComposeFailureException.class,
                            e -> assertThat(e.code()).isEqualTo(ComposeFailureCode.SOURCE_UNREADABLE));

            ComposeSpec sticker = spec(whiteBackground(), List.of(), List.of(), List.of(layer("sticker", 0, 0, 10, 10, 0, 1, 1.0)));
            assertThatThrownBy(() -> render(sticker, List.of(), Map.of()))
                    .isInstanceOfSatisfying(ComposeFailureException.class,
                            e -> assertThat(e.code()).isEqualTo(ComposeFailureCode.ASSET_MISSING));
            assertThatThrownBy(() -> render(sticker, List.of(), Map.of("sticker", new byte[]{9})))
                    .isInstanceOfSatisfying(ComposeFailureException.class,
                            e -> assertThat(e.code()).isEqualTo(ComposeFailureCode.ASSET_UNREADABLE));
        }
    }

    @Nested
    @DisplayName("레이어 — 스티커·구운 텍스트")
    class Layers {

        @Test
        @DisplayName("겹치면 zIndex가 큰 레이어가 위다 — 목록 순서가 아니라 zIndex 순서")
        void higherZIndexWins() {
            // 목록에는 위(z=2)가 먼저 온다 — 정렬하지 않으면 green이 위가 되는 배치
            ComposeSpec spec = spec(whiteBackground(), List.of(), List.of(), List.of(
                    layer("red", 30, 10, 30, 30, 0, 2, 1.0),
                    layer("green", 10, 10, 30, 30, 0, 1, 1.0)));

            BufferedImage result = decode(render(spec, List.of(), Map.of(
                    "red", solidPng(10, 10, RED), "green", solidPng(10, 10, GREEN))));

            assertThat(pixel(result, 35, 25)).isEqualTo(RED);
            assertThat(pixel(result, 15, 25)).isEqualTo(GREEN);
        }

        @Test
        @DisplayName("회전은 중심 기준이다 — 45도 돌리면 원래 모서리 자리가 빈다")
        void rotationAboutCenter() {
            ComposeSpec spec = spec(whiteBackground(), List.of(), List.of(), List.of(
                    layer("red", 30, 30, 40, 40, 45, 1, 1.0)));

            BufferedImage result = decode(render(
                    spec, List.of(), Map.of("red", solidPng(10, 10, RED))));

            assertCloseTo(pixel(result, 50, 50), RED, 3);
            assertCloseTo(pixel(result, 33, 33), WHITE, 3);
        }

        @Test
        @DisplayName("opacity 0.5인 레이어는 배경과 반씩 섞인다")
        void opacityBlends() {
            ComposeSpec spec = spec(whiteBackground(), List.of(), List.of(), List.of(
                    layer("red", 30, 30, 40, 40, 0, 1, 0.5)));

            BufferedImage result = decode(render(
                    spec, List.of(), Map.of("red", solidPng(10, 10, RED))));

            assertCloseTo(pixel(result, 50, 50), new Color(255, 128, 128), 3);
        }
    }

    @Nested
    @DisplayName("셀 누끼 — 서버는 아무것도 그리지 않는다")
    class CellCutouts {

        // 누끼는 프론트가 원본 픽셀에 구워서 올린다. 서버가 뭔가를 덧그리면
        // (과거의 가짜 비네트·녹색 링) 누끼 딴 사진 위에 장식이 겹친다 — 그 회귀를 여기서 막는다.
        // 옛 스냅샷에는 cellCutouts=true인 Job이 남아 있으므로 "무시"가 계약이다
        @Test
        @DisplayName("cellCutouts가 켜져 있어도 사진이 그대로다 — 비네트도 링도 없다")
        void cutoutFlagDrawsNothing() {
            ComposeSpec spec = new ComposeSpec(200, 200, whiteBackground(),
                    List.of(new Slot(20, 20, 160, 160)), List.of(true), List.of());

            BufferedImage result = decode(render(spec,
                    List.of(solidPng(10, 10, RED)), Map.of()));

            assertThat(pixel(result, 100, 100)).isEqualTo(RED);   // 중앙
            assertThat(pixel(result, 25, 25)).isEqualTo(RED);     // 모서리 — 비네트가 어둡히던 자리
            assertThat(pixel(result, 100, 22)).isEqualTo(RED);    // 테두리 — 녹색 링이 있던 자리
        }
    }

    @Nested
    @DisplayName("썸네일 — 원본과 함께 나오는 축소본")
    class Thumbnail {

        @Test
        @DisplayName("세로형(600x1800)은 긴 변이 512로 줄고 비율이 유지된다 — 171x512")
        void portraitScaledToLongEdge() {
            RenderResult result = render(
                    new ComposeSpec(600, 1800, new BackgroundAttributes.Color("#FF0000"),
                            List.of(), List.of(), List.of()),
                    List.of(), Map.of());

            BufferedImage thumb = decode(result.thumbnailJpeg());
            assertThat(thumb.getHeight()).isEqualTo(512);
            assertThat(thumb.getWidth()).isEqualTo(171);   // 600 × (512/1800) 반올림
        }

        @Test
        @DisplayName("가로형(1200x400)은 512x171이다 — 절반 축소를 한 번 거치는 경로")
        void landscapeScaledThroughHalving() {
            RenderResult result = render(
                    new ComposeSpec(1200, 400, new BackgroundAttributes.Color("#FF0000"),
                            List.of(), List.of(), List.of()),
                    List.of(), Map.of());

            BufferedImage thumb = decode(result.thumbnailJpeg());
            assertThat(thumb.getWidth()).isEqualTo(512);
            assertThat(thumb.getHeight()).isEqualTo(171);
        }

        @Test
        @DisplayName("원본이 512보다 작으면 확대하지 않는다 — 100x100 그대로")
        void neverUpscaled() {
            RenderResult result = render(
                    spec(whiteBackground(), List.of(), List.of(), List.of()),
                    List.of(), Map.of());

            BufferedImage thumb = decode(result.thumbnailJpeg());
            assertThat(thumb.getWidth()).isEqualTo(100);
            assertThat(thumb.getHeight()).isEqualTo(100);
        }

        @Test
        @DisplayName("썸네일은 JPEG이다 — 매직 바이트 FF D8")
        void thumbnailIsJpeg() {
            RenderResult result = render(
                    spec(whiteBackground(), List.of(), List.of(), List.of()),
                    List.of(), Map.of());

            assertThat(result.thumbnailJpeg()[0]).isEqualTo((byte) 0xFF);
            assertThat(result.thumbnailJpeg()[1]).isEqualTo((byte) 0xD8);
        }

        @Test
        @DisplayName("축소 후에도 내용이 같은 상대 위치에 있다 (JPEG 손실 감안 ±8)")
        void contentSurvivesScaling() {
            // 1024 캔버스 왼쪽 절반이 파랑 슬롯 → 512 썸네일에서도 왼쪽 절반이 파랑
            ComposeSpec spec = new ComposeSpec(1024, 1024, whiteBackground(),
                    List.of(new Slot(0, 0, 512, 1024)), List.of(), List.of());

            RenderResult result = render(spec, List.of(solidPng(10, 10, BLUE)), Map.of());

            BufferedImage thumb = decode(result.thumbnailJpeg());
            assertThat(thumb.getWidth()).isEqualTo(512);
            assertCloseTo(pixel(thumb, 128, 256), BLUE, 8);
            assertCloseTo(pixel(thumb, 384, 256), WHITE, 8);
        }
    }

    @Nested
    @DisplayName("입력 검증 — 조용히 엉킨 그림 대신 예외")
    class InputValidation {

        @Test
        @DisplayName("원본 수가 슬롯 수와 다르면 거부한다")
        void sourceCountMismatch() {
            ComposeSpec spec = spec(whiteBackground(),
                    List.of(new Slot(5, 5, 40, 40), new Slot(55, 5, 40, 40)),
                    List.of(), List.of());

            assertThatThrownBy(() -> render(spec, List.of(solidPng(10, 10, RED)), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("스펙이 참조하는 이미지가 assets에 없으면 어떤 key인지 말하고 거부한다")
        void missingAssetNamed() {
            ComposeSpec spec = spec(whiteBackground(), List.of(), List.of(), List.of(
                    layer("missing-key", 0, 0, 10, 10, 0, 1, 1.0)));

            assertThatThrownBy(() -> render(spec, List.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing-key");
        }

        @Test
        @DisplayName("이미지가 아닌 바이트는 거부한다")
        void undecodableBytesRejected() {
            ComposeSpec spec = spec(whiteBackground(),
                    List.of(new Slot(5, 5, 40, 40)), List.of(), List.of());

            assertThatThrownBy(() -> render(
                    spec, List.of("이미지 아님".getBytes()), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── fixtures ──────────────────────────────

    // 픽셀 단언은 무손실이어야 성립한다 — JPEG로 재면 값이 정확히 안 맞는다.
    // 그래서 여기서는 PNG로 고정한다. JPEG 출력의 픽셀 허용 오차는 별도로 잰다(☐1-3)
    private RenderResult render(ComposeSpec spec, List<byte[]> sourcePhotos,
                                Map<String, byte[]> assets) {
        return renderer.render(spec, sourcePhotos, assets, ImageFormat.PNG);
    }

    private static ComposeSpec spec(BackgroundAttributes background, List<Slot> slots,
                                    List<Boolean> cutouts, List<ComposeSpec.Layer> layers) {
        return new ComposeSpec(100, 100, background, slots, cutouts, layers);
    }

    private static BackgroundAttributes whiteBackground() {
        return new BackgroundAttributes.Color("#FFFFFF");
    }

    private static ComposeSpec.Layer layer(String source, double x, double y,
                                           double width, double height,
                                           double rotation, int zIndex, double opacity) {
        return new ComposeSpec.Layer(source, x, y, width, height, 1.0, rotation, zIndex, opacity);
    }

    private static byte[] solidPng(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return encode(image);
    }

    private static byte[] twoTonePng(int width, int height, Color left, Color right) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(left);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(right);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return encode(image);
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 기존 픽셀 테스트는 전부 원본을 본다 — 썸네일 검증은 Thumbnail 클래스가 따로 한다
    private static BufferedImage decode(RenderResult result) {
        return decode(result.full());
    }

    private static Color pixel(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y));
    }

    private static void assertCloseTo(Color actual, Color expected, int tolerance) {
        assertThat(Math.abs(actual.getRed() - expected.getRed()))
                .as("red (actual=%s, expected=%s)", actual, expected)
                .isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(actual.getGreen() - expected.getGreen()))
                .as("green (actual=%s, expected=%s)", actual, expected)
                .isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(actual.getBlue() - expected.getBlue()))
                .as("blue (actual=%s, expected=%s)", actual, expected)
                .isLessThanOrEqualTo(tolerance);
    }
}
