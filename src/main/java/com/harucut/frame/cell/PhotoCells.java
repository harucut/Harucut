package com.harucut.frame.cell;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.media.compose.ComposeSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 자유 사진 칸 검증 (ADR-0012 §2) — 위반을 모아 한 번에 400 GEN-002(data.violations: [{field, reason}]) */
public final class PhotoCells {

    public static final int COUNT = 4;
    public static final int MIN_SIZE = 100;

    private PhotoCells() {
    }

    /** null 이면 고정 레이아웃 — 검사하지 않는다 */
    public static void validate(List<PhotoCell> cells, FrameLayout layout) {
        if (cells == null) {
            return;
        }
        List<Map<String, String>> violations = new ArrayList<>();
        if (cells.size() != COUNT) {
            violations.add(violation("cells", "정확히 " + COUNT + "칸이어야 한다"));
        }
        Set<Integer> shots = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            PhotoCell cell = cells.get(i);
            String field = "cells[" + i + "]";
            if (cell == null) {
                violations.add(violation(field, "칸이 비었다"));
                continue;
            }
            if (cell.width() < MIN_SIZE || cell.height() < MIN_SIZE) {
                violations.add(violation(field, "너비·높이는 " + MIN_SIZE + "px 이상이어야 한다"));
            }
            if (cell.x() < 0 || cell.y() < 0 || (long) cell.x() + cell.width() > layout.canvasWidth()
                    || (long) cell.y() + cell.height() > layout.canvasHeight()) {
                violations.add(violation(field, "회전 전 칸이 캔버스(" + layout.canvasWidth() + "×" + layout.canvasHeight() + ") 안이어야 한다"));
            }
            if (!Double.isFinite(cell.rotation()) || cell.rotation() < -180 || cell.rotation() > 180) {
                violations.add(violation(field + ".rotation", "-180~180 도여야 한다"));
            }
            if (cell.shotIndex() < 0 || cell.shotIndex() >= COUNT || !shots.add(cell.shotIndex())) {
                violations.add(violation(field + ".shotIndex", "0~" + (COUNT - 1) + " 이 칸마다 한 번씩이어야 한다"));
            }
            shape(cell, field, violations);
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "자유 사진 칸 위반 " + violations.size() + "건",
                    Map.of("violations", violations));
        }
    }

    private static void shape(PhotoCell cell, String field, List<Map<String, String>> violations) {
        PhotoCell.Shape shape = cell.shape();
        if (shape == null || shape.type() == null) {
            violations.add(violation(field + ".shape", "모양(RECT·ELLIPSE)이 필요하다"));
            return;
        }
        switch (shape.type()) {
            case RECT -> {
                int radius = shape.cornerRadius() == null ? 0 : shape.cornerRadius();
                if (radius < 0 || radius > Math.min(cell.width(), cell.height()) / 2) {
                    violations.add(violation(field + ".shape.cornerRadius", "0~min(너비,높이)/2 여야 한다"));
                }
                if (shape.maskAssetId() != null) {
                    violations.add(violation(field + ".shape.maskAssetId", "MASK 에만 쓴다"));
                }
            }
            case ELLIPSE -> {
                if (shape.cornerRadius() != null || shape.maskAssetId() != null) {
                    violations.add(violation(field + ".shape", "ELLIPSE 는 cornerRadius·maskAssetId 를 쓰지 않는다"));
                }
            }
            case MASK -> violations.add(violation(field + ".shape", "이미지 마스크는 아직 지원하지 않는다"));
        }
    }

    /** 합성 스냅샷의 칸 사각형(회전 전) — 칸 순서 그대로 */
    public static List<FrameLayout.Slot> slots(List<PhotoCell> cells) {
        return cells.stream().map(cell -> new FrameLayout.Slot(cell.x(), cell.y(), cell.width(), cell.height())).toList();
    }

    /** 합성 스냅샷의 칸 그리기 정보(ADR-0012 §4) — 검증을 지난 칸이라 MASK 는 오지 않는다 */
    public static List<ComposeSpec.Cell> composeCells(List<PhotoCell> cells) {
        return cells.stream().map(cell -> composeCell(cell.rotation(), cell.shape(), cell.shotIndex())).toList();
    }

    public static ComposeSpec.Cell composeCell(double rotation, PhotoCell.Shape shape, int shotIndex) {
        ComposeSpec.CellShape type = switch (shape.type()) {
            case RECT -> ComposeSpec.CellShape.RECT;
            case ELLIPSE -> ComposeSpec.CellShape.ELLIPSE;
            case MASK -> throw new IllegalStateException("이미지 마스크 칸은 아직 그릴 수 없다");
        };
        return new ComposeSpec.Cell(rotation, type, shape.cornerRadius() == null ? 0 : shape.cornerRadius(), shotIndex);
    }

    private static Map<String, String> violation(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }
}
