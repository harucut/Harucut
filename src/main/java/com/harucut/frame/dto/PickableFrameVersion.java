package com.harucut.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.harucut.frame.enums.FrameType;

import java.time.LocalDateTime;

@Schema(description = "행사에 연결할 수 있는 프레임 발행본 — 프레임마다 최신 발행본 하나")
public record PickableFrameVersion(
        @Schema(description = "EventSettings.frames[].frameVersionId 에 넣는 값", example = "41") Long frameVersionId,
        @Schema(description = "원본 프레임 ID", example = "7") Long frameId,
        @Schema(description = "발행 번호", example = "3") int versionNo,
        @Schema(description = "레이아웃 — EventSettings.frames[].layoutType 과 같아야 한다", example = "CLASSIC") FrameType frameType,
        @Schema(description = "원본 프레임의 현재 제목", example = "봄 웨딩") String title,
        @Schema(description = "원본 프레임 미리보기의 presigned GET URL(24시간) — 참고용, 발행본 자체와 다를 수 있다") String previewUrl,
        @Schema(description = "SYSTEM(기본 제공) · WORKSPACE(이 공간)", example = "WORKSPACE") Source source,
        @Schema(description = "발행 시각") LocalDateTime publishedAt
) {
    public enum Source { SYSTEM, WORKSPACE }
}
