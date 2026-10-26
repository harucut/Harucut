package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.event.enums.EventStatus;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Schema(description = "참가 화면이 보는 행사 정보 — 운영 설정·다른 행사·전체 카탈로그는 없다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicEventResponse(
        @Schema(description = "행사 public id") String eventId,
        @Schema(description = "행사명") String name,
        @Schema(description = "상태") EventStatus status,
        @Schema(description = "지금 새 참가·촬영을 받는가") boolean captureOpen,
        @Schema(description = "개인 휴대폰 참가 허용") boolean byodEnabled,
        @Schema(description = "부스 참가 허용") boolean boothEnabled,
        @Schema(description = "현재 경험 버전 번호") int experienceVersion,
        @Schema(description = "허용 레이아웃") List<FrameType> allowedLayouts,
        @Schema(description = "고를 수 있는 프레임 — 이 목록만 보여 준다") List<OfferedFrame> frames,
        @Schema(description = "동의 항목") List<EventSettings.ConsentItem> consentItems,
        @Schema(description = "페이지 테마(P2A 자리표시자)") JsonNode theme
) {

    @Schema(description = "제공 프레임")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OfferedFrame(
            @Schema(description = "프레임 발행본 ID") Long frameVersionId,
            @Schema(description = "레이아웃") FrameType layoutType,
            @Schema(description = "표시 순서") int sortOrder,
            @Schema(description = "기본 선택") boolean isDefault,
            @Schema(description = "미리보기 이미지 짧은 URL") String previewUrl
    ) {
    }
}
