package com.harucut.frame.cell;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.enums.FrameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("자유 사진 칸 검증")
class PhotoCellsTest {

    private static final PhotoCell.Shape RECT = new PhotoCell.Shape(PhotoCell.ShapeType.RECT, 40, null);
    private static final PhotoCell.Shape ELLIPSE = new PhotoCell.Shape(PhotoCell.ShapeType.ELLIPSE, null, null);

    @Test
    @DisplayName("4칸·캔버스 안·shotIndex 0~3 한 번씩·RECT/ELLIPSE 는 통과, null 은 고정 레이아웃이라 검사하지 않는다")
    void valid() {
        assertThatCode(() -> PhotoCells.validate(cells(), FrameType.CLASSIC.getLayout())).doesNotThrowAnyException();
        assertThatCode(() -> PhotoCells.validate(null, FrameType.CLASSIC.getLayout())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("위반은 모아서 한 번에 400 GEN-002 + data.violations[{field, reason}]")
    void violations() {
        List<PhotoCell> cells = new ArrayList<>(cells());
        cells.set(0, new PhotoCell(-1, 200, 1700, 1200, 0, RECT, 0));                                   // 캔버스 밖
        cells.set(1, new PhotoCell(150, 1480, 50, 1200, 200, RECT, 0));                                 // 너무 작음·회전·shot 중복
        cells.set(2, new PhotoCell(150, 2760, 1700, 1200, 0, new PhotoCell.Shape(PhotoCell.ShapeType.RECT, 900, null), 2)); // 반지름
        cells.set(3, new PhotoCell(150, 4040, 1700, 1200, 0, new PhotoCell.Shape(PhotoCell.ShapeType.MASK, null, "Ab12"), 3)); // 마스크

        assertThatThrownBy(() -> PhotoCells.validate(cells, FrameType.CLASSIC.getLayout()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field")).contains(
                            "cells[0]", "cells[1]", "cells[1].rotation", "cells[1].shotIndex", "cells[2].shape.cornerRadius", "cells[3].shape");
                });
        assertThatThrownBy(() -> PhotoCells.validate(cells().subList(0, 3), FrameType.CLASSIC.getLayout()))
                .isInstanceOf(BusinessException.class);
        List<PhotoCell> noShape = new ArrayList<>(cells());
        noShape.set(0, new PhotoCell(150, 200, 1700, 1200, 0, null, 0));
        assertThatThrownBy(() -> PhotoCells.validate(noShape, FrameType.CLASSIC.getLayout())).isInstanceOf(BusinessException.class);
        List<PhotoCell> ellipseRadius = new ArrayList<>(cells());
        ellipseRadius.set(0, new PhotoCell(150, 200, 1700, 1200, 0, new PhotoCell.Shape(PhotoCell.ShapeType.ELLIPSE, 10, null), 0));
        assertThatThrownBy(() -> PhotoCells.validate(ellipseRadius, FrameType.CLASSIC.getLayout())).isInstanceOf(BusinessException.class);
    }

    /** CLASSIC 캔버스(2000×6000) 안의 기울어진 4칸, 촬영 순서는 거꾸로 */
    static List<PhotoCell> cells() {
        return List.of(
                new PhotoCell(150, 200, 1700, 1200, -4.5, RECT, 3),
                new PhotoCell(150, 1480, 1700, 1200, 3, ELLIPSE, 2),
                new PhotoCell(150, 2760, 1700, 1200, 0, RECT, 1),
                new PhotoCell(150, 4040, 1700, 1200, 180, ELLIPSE, 0));
    }
}
