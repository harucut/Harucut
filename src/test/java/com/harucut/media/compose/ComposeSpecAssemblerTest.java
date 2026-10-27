package com.harucut.media.compose;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.cell.PhotoCell;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ComposeSpecAssembler")
class ComposeSpecAssemblerTest {

    private static final String PHOTO_KEY = "uploads/users/abc/components/photo.png";
    private static final String STICKER_KEY = "uploads/users/abc/components/sticker.png";
    private static final String RENDERED_KEY = "uploads/users/abc/components/text.png";

    private final ComposeSpecAssembler assembler = new ComposeSpecAssembler();

    @Nested
    @DisplayName("프레임 → 스펙 변환")
    class Assemble {

        @Test
        @DisplayName("자유 사진 칸(ADR-0012) — 슬롯은 칸 사각형, 칸마다 회전·모양·반지름·순번을 싣는다. 고정 레이아웃이면 cells 없음")
        void freeCellsMapped() {
            Frame frame = colorFrame();
            frame.assignPhotoCells(List.of(
                    new PhotoCell(150, 200, 1700, 1200, -4.5, new PhotoCell.Shape(PhotoCell.ShapeType.RECT, 40, null), 3),
                    new PhotoCell(150, 1480, 1700, 1200, 3, new PhotoCell.Shape(PhotoCell.ShapeType.ELLIPSE, null, null), 2),
                    new PhotoCell(150, 2760, 1700, 1200, 0, new PhotoCell.Shape(PhotoCell.ShapeType.RECT, null, null), 1),
                    new PhotoCell(100, 4000, 1800, 1300, 180, new PhotoCell.Shape(PhotoCell.ShapeType.ELLIPSE, null, null), 0)));

            ComposeSpec spec = assembler.assemble(frame, null);

            assertThat(spec.slots().get(3)).isEqualTo(new com.harucut.frame.layout.FrameLayout.Slot(100, 4000, 1800, 1300));
            assertThat(spec.cells()).containsExactly(
                    new ComposeSpec.Cell(-4.5, ComposeSpec.CellShape.RECT, 40, 3),
                    new ComposeSpec.Cell(3, ComposeSpec.CellShape.ELLIPSE, 0, 2),
                    new ComposeSpec.Cell(0, ComposeSpec.CellShape.RECT, 0, 1),
                    new ComposeSpec.Cell(180, ComposeSpec.CellShape.ELLIPSE, 0, 0));
            assertThat(assembler.assemble(colorFrame(), null).cells()).isNull();
        }

        @Test
        @DisplayName("캔버스·슬롯은 frameType에서, cellCutouts는 프레임에서 온다")
        void canvasSlotsCutoutsMapped() {
            Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Color("#FFE4E1"),
                    List.of(true, false, true, false));

            ComposeSpec spec = assembler.assemble(frame, null);

            assertThat(spec.canvasWidth()).isEqualTo(2000);
            assertThat(spec.canvasHeight()).isEqualTo(6000);
            assertThat(spec.slots()).isEqualTo(FrameType.CLASSIC.getLayout().slots());
            assertThat(spec.cellCutouts()).containsExactly(true, false, true, false);
        }

        @Test
        @DisplayName("PHOTO·STICKER는 source가, TEXT는 renderedKey가 레이어 참조가 된다")
        void layerSourcesByType() {
            Frame frame = colorFrame();
            frame.addComponent(component(ComponentType.PHOTO, PHOTO_KEY, null));
            frame.addComponent(component(ComponentType.STICKER, STICKER_KEY, null));
            frame.addComponent(component(ComponentType.TEXT, "봄 여행", RENDERED_KEY));

            ComposeSpec spec = assembler.assemble(frame, null);

            assertThat(spec.layers()).extracting(ComposeSpec.Layer::source)
                    .containsExactly(PHOTO_KEY, STICKER_KEY, RENDERED_KEY);
        }

        @Test
        @DisplayName("null 보정: width/height는 0, scale·opacity는 1이 된다")
        void nullDefaults() {
            Frame frame = colorFrame();
            frame.addComponent(FrameComponent.builder()
                    .source(PHOTO_KEY).type(ComponentType.PHOTO).build());

            ComposeSpec.Layer layer = assembler.assemble(frame, null).layers().get(0);

            assertThat(layer.width()).isZero();
            assertThat(layer.height()).isZero();
            assertThat(layer.scale()).isEqualTo(1.0);
            assertThat(layer.opacity()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("styleJson.opacity가 레이어에 실리고 0~1로 잘린다")
        void opacityFromStyleClamped() {
            Frame frame = colorFrame();
            frame.addComponent(FrameComponent.builder()
                    .source(PHOTO_KEY).type(ComponentType.PHOTO)
                    .style(Map.of("opacity", 0.4)).build());
            frame.addComponent(FrameComponent.builder()
                    .source(STICKER_KEY).type(ComponentType.STICKER)
                    .style(Map.of("opacity", 1.5)).build());

            List<ComposeSpec.Layer> layers = assembler.assemble(frame, null).layers();

            assertThat(layers.get(0).opacity()).isEqualTo(0.4);
            assertThat(layers.get(1).opacity()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("합성 가능성 관문 — S3에 없는 참조는 400")
    class ComposabilityGate {

        @Test
        @DisplayName("renderedKey 없는 TEXT는 GEN-002다 — 1단계에서 미뤄둔 검증의 자리")
        void unbakedTextRejected() {
            Frame frame = colorFrame();
            frame.addComponent(component(ComponentType.TEXT, "안 구운 텍스트", null));

            assertThatThrownBy(() -> assembler.assemble(frame, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("관리 key가 아닌 스티커(외부 URL)는 GEN-002다")
        void externalStickerRejected() {
            Frame frame = colorFrame();
            frame.addComponent(component(ComponentType.STICKER,
                    "https://cdn.example.com/stickers/heart.png", null));

            assertThatThrownBy(() -> assembler.assemble(frame, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("관리 key가 아닌 IMAGE 배경도 GEN-002다")
        void externalBackgroundRejected() {
            Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image("https://cdn.example.com/bg.png", 0.8, null));

            assertThatThrownBy(() -> assembler.assemble(frame, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
        }

        @Test
        @DisplayName("관리 key인 IMAGE 배경은 그대로 통과한다")
        void managedBackgroundPasses() {
            Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image("uploads/users/abc/components/bg.png", 0.8, null));

            ComposeSpec spec = assembler.assemble(frame, null);

            assertThat(spec.background()).isEqualTo(
                    new BackgroundAttributes.Image("uploads/users/abc/components/bg.png", 0.8, null));
        }
    }

    @Nested
    @DisplayName("배경색 덮어쓰기")
    class BackgroundColorOverride {

        @Test
        @DisplayName("COLOR 배경에 색을 주면 스펙의 배경이 그 색으로 바뀐다")
        void colorBackgroundReplaced() {
            ComposeSpec spec = assembler.assemble(colorFrame(), "#112233");

            assertThat(spec.background())
                    .isEqualTo(new BackgroundAttributes.Color("#112233"));
        }

        @Test
        @DisplayName("IMAGE 배경 프레임에 색을 주면 GEN-002다 — 조용한 무시 금지")
        void imageBackgroundWithColorRejected() {
            Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image("uploads/users/abc/components/bg.png", 0.8, null));

            assertThatThrownBy(() -> assembler.assemble(frame, "#112233"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
    }

    /*
     * 저장 시점 소유권 검사가 생기기 전에 저장된 프레임이 있을 수 있다 — 합성이 마지막 관문이다.
     */
    @Nested
    @DisplayName("사용자 프레임의 자산 소유권")
    class Ownership {

        private final User owner = User.localUser("owner@harucut.com", "encoded", "하루컷");
        private final String mine = S3Keys.userRoot(owner.getPublicId()) + "components/";
        private final String foreign = "uploads/users/ZzZzZzZzZz99/components/";

        @Test
        @DisplayName("남의 STICKER key 가 들어 있으면 403 이다")
        void foreignStickerForbidden() {
            Frame frame = ownedColorFrame();
            frame.addComponent(component(ComponentType.STICKER, foreign + "s.png", null));

            assertThatThrownBy(() -> assembler.assemble(frame, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("남의 PHOTO·TEXT 자산도 403 이다")
        void foreignPhotoAndTextForbidden() {
            Frame photoFrame = ownedColorFrame();
            photoFrame.addComponent(component(ComponentType.PHOTO, foreign + "p.png", null));
            Frame textFrame = ownedColorFrame();
            textFrame.addComponent(component(ComponentType.TEXT, "봄", foreign + "t.png"));

            assertThatThrownBy(() -> assembler.assemble(photoFrame, null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> assembler.assemble(textFrame, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("남의 배경 이미지도 403 이다")
        void foreignBackgroundForbidden() {
            Frame frame = Frame.owned(owner, "내 프레임", "", mine + "p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image(foreign + "bg.png", 0.8, null));

            assertThatThrownBy(() -> assembler.assemble(frame, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("내 자산만 쓰면 통과한다")
        void ownAssetsPass() {
            Frame frame = Frame.owned(owner, "내 프레임", "", mine + "p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image(mine + "bg.png", 0.8, null));
            frame.addComponent(component(ComponentType.PHOTO, mine + "p.png", null));
            frame.addComponent(component(ComponentType.STICKER, mine + "s.png", null));
            frame.addComponent(component(ComponentType.TEXT, "봄", mine + "t.png"));

            assertThat(assembler.assemble(frame, null).layers()).hasSize(3);
        }

        private Frame ownedColorFrame() {
            return Frame.owned(owner, "내 프레임", "", mine + "p.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Color("#FFE4E1"));
        }
    }

    // ── fixtures ──────────────────────────────

    private static Frame colorFrame() {
        return Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                new BackgroundAttributes.Color("#FFE4E1"));
    }

    private static FrameComponent component(ComponentType type, String source, String renderedKey) {
        return FrameComponent.builder()
                .source(source).type(type).renderedKey(renderedKey)
                .x(120.5).y(220.0).width(360.0).height(480.0)
                .scale(1.0).rotation(45.0).zIndex(3)
                .build();
    }
}
