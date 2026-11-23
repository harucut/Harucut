package com.harucut.media.compose;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.layout.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 합성 한 건의 자기 완결적 렌더 스펙 — 요청 시점의 프레임에서 파생해 Job에 스냅샷으로 저장한다.
// 프레임이 나중에 수정·삭제돼도 재실행은 이 스냅샷만 보므로, 사용자가 요청할 때 본 그대로 그려진다.
// 그리기 순서 계약(프론트 drawFrameOnce와 동일): 배경(색→이미지) → 원본 4장(슬롯, cover)
// → 레이어(zIndex 오름차순).
// cellCutouts는 서버가 그리는 대상이 아니다 — 누끼는 프론트가 원본에 구워서 올리고,
// 이 플래그는 "프론트가 누끼를 딸 셀"이라는 표시로만 스냅샷에 남는다.
// cells 는 자유 사진 칸(ADR-0012)일 때만 — 칸마다 회전·모양·입력 순번(shotIndex). 없으면 slots 를 사각형 그대로, 원본 i 를 칸 i 에
public record ComposeSpec(
        int canvasWidth,
        int canvasHeight,
        BackgroundAttributes background,
        List<FrameLayout.Slot> slots,
        List<Boolean> cellCutouts,
        List<Layer> layers,
        List<Cell> cells
) {

    public ComposeSpec {
        slots = List.copyOf(slots);
        cellCutouts = List.copyOf(cellCutouts);
        layers = List.copyOf(layers);
        if (cells != null) {
            cells = List.copyOf(cells);
            if (cells.size() != slots.size()) {
                throw new IllegalArgumentException("칸 정보 수가 슬롯 수와 다르다");
            }
            Set<Integer> shots = new HashSet<>();
            for (Cell cell : cells) {
                if (cell.shotIndex() < 0 || cell.shotIndex() >= slots.size() || !shots.add(cell.shotIndex())) {
                    throw new IllegalArgumentException("shotIndex 는 0~" + (slots.size() - 1) + " 이 칸마다 한 번씩이어야 한다");
                }
            }
        }
    }

    // 고정 레이아웃(자유 칸이 없던 시절)의 생성자 — 옛 스냅샷·호출부 호환
    public ComposeSpec(int canvasWidth, int canvasHeight, BackgroundAttributes background, List<FrameLayout.Slot> slots,
                       List<Boolean> cellCutouts, List<Layer> layers) {
        this(canvasWidth, canvasHeight, background, slots, cellCutouts, layers, null);
    }

    /**
     * 자유 사진 칸 하나의 그리기 정보 — 사각형은 같은 순서의 slot 이다.
     * rotation 은 칸 중심 기준(도, 시계 방향), shape 는 칸 안의 클립, shotIndex 는 이 칸에 넣을 원본 순번
     */
    public record Cell(double rotation, CellShape shape, int cornerRadius, int shotIndex) {
    }

    public enum CellShape {
        RECT, ELLIPSE
    }

    // 스펙이 참조하는 이미지 자산 전부: IMAGE 배경 + 레이어 (중복 제거, 등장 순서 유지).
    // 서버 실행기와 Lambda 핸들러가 이 목록으로 내려받는다 — 계산이 두 벌이면 어긋난다
    public List<String> referencedAssetKeys() {
        Set<String> keys = new LinkedHashSet<>();
        if (background instanceof BackgroundAttributes.Image image) {
            keys.add(image.key());
        }
        for (Layer layer : layers) {
            keys.add(layer.source());
        }
        return List.copyOf(new ArrayList<>(keys));
    }

    // 프레임 컴포넌트에서 파생된 비트맵 층 하나. 텍스트는 이미 구운 이미지(renderedKey)로
    // 오므로 여기서는 전부 "이미지 + 위치"다 — 컴포넌트 타입 구분이 필요 없다.
    // 변환 의미는 프론트 drawThemeOverlay와 동일: 이미지를 width×height로 늘린 뒤
    // 중심 기준으로 scale·rotation(도)을 적용하고 opacity(styleJson에서 파생, 0~1)로 얹는다
    public record Layer(
            // S3 key — 합성기가 가져올 수 있는 참조여야 한다 (스펙 조립 시 보장. 정적 경로는 없다)
            String source,
            double x, double y,
            double width, double height,
            double scale,
            double rotation,
            int zIndex,
            double opacity
    ) {
    }
}
