package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.event.enums.EventStatus;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "페이지 테마 — 기본값이 채워지고 자산은 URL") ResolvedPageTheme theme,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "참가자 자유 문구 — 이 행사가 허용할 때만, 아니면 null(프레임 문구 고정)", nullable = true) OfferedFreeText freeText
) {

    @Schema(description = "참가자 자유 문구 정책 — 미리보기·합성은 서버가 같은 렌더로 굽는다")
    public record OfferedFreeText(
            @Schema(description = "문구 칸 — 캔버스 대비 비율 0~1") FreeTextPolicy.Box box,
            @Schema(description = "줄바꿈 제외 글자 수 상한", example = "20") int maxLength,
            @Schema(description = "줄 수 상한", example = "1") int maxLines,
            @Schema(description = "고를 수 있는 스타일") List<TextStyleResponse> styles
    ) {
        public static OfferedFreeText of(FreeTextPolicy policy) {
            return new OfferedFreeText(policy.box(), policy.maxLengthOrDefault(), policy.maxLinesOrDefault(),
                    policy.styles().stream().map(TextStyleResponse::of).toList());
        }
    }

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
