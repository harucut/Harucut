package com.harucut.frame.version;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.cell.PhotoCell;
import com.harucut.frame.enums.FrameType;

import java.util.List;
import java.util.Map;

/**
 * 발행본의 편집 문서 v2 (ADR-0006 §4, R5 6절 표). compose-core 가 직접 읽는 형식이 아니다 —
 * 행사 합성(P2A)은 이 문서를 서버 어댑터로 ComposeSpec 으로 바꾼다.
 *
 * <p>사진 칸은 레이아웃이 고정한 4칸이고(slotBindings 는 입력 순서만), 레이어는 이미지와 구운 글자다.
 * 기존 PHOTO 는 "사진 칸"이 아니라 이미지 레이어라 kind=IMAGE 로 간다.
 * url 은 응답 전용이라 저장하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameSpecV2(
        int schemaVersion,
        Canvas canvas,
        BackgroundAttributes background,
        Layout layout,
        List<Boolean> cellCutouts,
        List<SlotBinding> slotBindings,
        List<Layer> layers,
        EditPolicy editPolicy
) {

    public static final int SCHEMA_VERSION = 2;

    public record Canvas(int width, int height) {
    }

    /**
     * cells 는 자유 사진 칸 프레임일 때만(ADR-0012) — 그때 slots 는 각 칸의 회전 전 사각형이라 옛 화면도 사각 미리보기로 읽는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Layout(FrameType frameType, List<Slot> slots, List<Cell> cells) {

        public Layout(FrameType frameType, List<Slot> slots) {
            this(frameType, slots, null);
        }
    }

    /** 자유 사진 칸 — slotId 는 slots 와 같은 순서(s1~s4), shotIndex 는 이 칸에 넣을 촬영 순번 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(String slotId, int x, int y, int width, int height, double rotation, PhotoCell.Shape shape, int shotIndex) {
    }

    public record Slot(String slotId, int x, int y, int width, int height) {
    }

    // 발행본에는 "몇 번째 입력이 어느 칸에" 만 둔다 — 실제 사진은 합성 작업 스냅샷에만 있다
    public record SlotBinding(String slotId, int sourceIndex) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Layer(
            String role,
            String kind,
            String assetId,
            String url,
            double x, double y,
            double width, double height,
            double scale,
            double rotation,
            int zIndex,
            double opacity,
            Text text
    ) {
        public Layer withUrl(String url) {
            return new Layer(role, kind, assetId, url, x, y, width, height, scale, rotation, zIndex, opacity, text);
        }
    }

    public record Text(String content, Map<String, Object> style) {
    }

    // P1 은 FIXED 고정. 참가자 제한 편집은 P2A 에서 늘린다
    public record EditPolicy(String mode) {
        public static final EditPolicy FIXED = new EditPolicy("FIXED");
    }

    public FrameSpecV2 withUrls(BackgroundAttributes resolvedBackground, List<Layer> resolvedLayers) {
        return new FrameSpecV2(schemaVersion, canvas, resolvedBackground, layout, cellCutouts, slotBindings,
                resolvedLayers, editPolicy);
    }
}
