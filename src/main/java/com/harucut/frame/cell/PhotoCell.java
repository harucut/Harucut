package com.harucut.frame.cell;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자유 사진 칸 하나 (ADR-0012, R5 6절) — 네 칸은 유지하고 칸마다 위치·크기·회전·모양과 몇 번째 사진을 넣을지를 정한다.
 * 프레임에 칸이 없으면(null) 지금처럼 frameType 의 고정 레이아웃이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "PhotoCell", description = "자유 사진 칸 — 캔버스 px, 회전 전 사각형 기준")
public record PhotoCell(
        @Schema(description = "왼쪽 위 x(회전 전)", example = "150") int x,
        @Schema(description = "왼쪽 위 y(회전 전)", example = "200") int y,
        @Schema(description = "너비(최소 100)", example = "1700") int width,
        @Schema(description = "높이(최소 100)", example = "1200") int height,
        @Schema(description = "칸 중심 기준 회전(도, -180~180, 시계 방향)", example = "-4.5") double rotation,
        @Schema(description = "칸 모양") Shape shape,
        @Schema(description = "이 칸에 넣을 촬영 순번(0~3) — 네 칸에서 서로 달라야 한다", example = "0") int shotIndex
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PhotoCellShape", description = """
            `RECT` 사각형(`cornerRadius` 로 둥근 모서리, 0~min(width,height)/2) · `ELLIPSE` 칸에 내접하는 타원 ·
            `MASK` 이미지 마스크(`maskAssetId`) — **아직 지원하지 않는다(400)**, 자리만 잡아 둔다""")
    public record Shape(
            ShapeType type,
            @Schema(description = "RECT 만 — 모서리 반지름 px", example = "40") Integer cornerRadius,
            @Schema(description = "MASK 만 — 마스크 자산 public id(아직 400)") String maskAssetId
    ) {
    }

    public enum ShapeType {
        RECT, ELLIPSE, MASK
    }
}
