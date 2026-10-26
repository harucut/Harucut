package com.harucut.event.dto;

import com.harucut.event.enums.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "행사(운영자 화면)")
public record EventResponse(
        @Schema(description = "행사 public id", example = "Ev7xK2mQ9pLa") String eventId,
        @Schema(description = "공간 public id", example = "Ws1aB2cD3eF4") String workspaceId,
        @Schema(description = "상태", example = "LIVE") EventStatus status,
        @Schema(description = "일시 중지 여부", example = "false") boolean paused,
        @Schema(description = "지금 새 참가·촬영을 받는가(LIVE·중지 아님·촬영 기간 안)", example = "true") boolean captureOpen,
        @Schema(description = "PATCH 에 그대로 보낼 낙관적 잠금 값", example = "3") int settingsVersion,
        @Schema(description = "설정 초안") EventSettings settings,
        @Schema(description = "현재 게시된 경험 버전 번호 — 게시 전이면 null", example = "2") Integer currentExperienceVersion,
        @Schema(description = "참가 링크 경로", example = "/e/wedding-1027") String joinUrlPath
) {
}
