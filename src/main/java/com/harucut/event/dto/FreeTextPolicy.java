package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.text.TextStyle;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.HashSet;
import java.util.List;

/**
 * 참가자 자유 문구 정책(R5 D06). 상속 설정이라 「끔」과 「상속」을 나눈다 — {enabled:false} 는 이 단계에서 명시적으로 끔,
 * 키가 없거나 null 이면 위 단계 값을 따른다(모든 단계가 비면 FIXED).
 * box 는 캔버스 대비 비율(0~1) — 레이아웃마다 캔버스 크기가 달라서다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "참가자 자유 문구 — {enabled:false} 는 끔(FIXED), null 은 위 단계 값을 따른다")
public record FreeTextPolicy(
        @Schema(description = "켬·끔 — 필수", requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled,
        @Schema(description = "문구 칸 — 캔버스 대비 비율 0~1. 켤 때 필수") Box box,
        @Schema(description = "고를 수 있는 스타일 id(GET /api/public/text-styles 의 부분집합). 켤 때 1개 이상") List<String> styleIds,
        @Schema(description = "줄바꿈 제외 글자 수 상한 1~30(기본 20)", example = "20") Integer maxLength,
        @Schema(description = "줄 수 상한 1~3(기본 1)", example = "1") Integer maxLines
) {

    public static final int DEFAULT_MAX_LENGTH = 20;
    public static final int MAX_LENGTH_LIMIT = 30;
    public static final int DEFAULT_MAX_LINES = 1;
    public static final int MAX_LINES_LIMIT = 3;

    public record Box(
            @Schema(example = "0.1") Double x,
            @Schema(example = "0.86") Double y,
            @Schema(example = "0.8") Double width,
            @Schema(example = "0.08") Double height
    ) {
    }

    public boolean on() {
        return Boolean.TRUE.equals(enabled);
    }

    public int maxLengthOrDefault() {
        return maxLength == null ? DEFAULT_MAX_LENGTH : maxLength;
    }

    public int maxLinesOrDefault() {
        return maxLines == null ? DEFAULT_MAX_LINES : maxLines;
    }

    public List<TextStyle> styles() {
        return styleIds == null ? List.of() : styleIds.stream().map(id -> TextStyle.byId(id).orElseThrow()).toList();
    }

    /** 저장 전 검사 — 틀리면 400 GEN-002. 끈 정책은 다른 키를 보지 않는다 */
    public void validate() {
        if (enabled == null) {
            throw invalid("freeText.enabled 는 필수입니다 — 끄려면 false, 위 단계 값을 따르려면 freeText 를 null 로 보냅니다.");
        }
        if (!enabled) {
            return;
        }
        if (box == null || box.x() == null || box.y() == null || box.width() == null || box.height() == null) {
            throw invalid("freeText.box 의 x·y·width·height 가 모두 필요합니다.");
        }
        if (box.x() < 0 || box.y() < 0 || box.width() <= 0 || box.height() <= 0
                || box.x() + box.width() > 1.0 + 1e-9 || box.y() + box.height() > 1.0 + 1e-9) {
            throw invalid("freeText.box 는 캔버스 안(0~1 비율)이어야 합니다.");
        }
        if (styleIds == null || styleIds.isEmpty()) {
            throw invalid("freeText.styleIds 가 하나 이상 필요합니다.");
        }
        if (new HashSet<>(styleIds).size() != styleIds.size()
                || styleIds.stream().anyMatch(id -> TextStyle.byId(id).isEmpty())) {
            throw invalid("freeText.styleIds 에 없는 스타일이나 중복이 있습니다.");
        }
        if (maxLength != null && (maxLength < 1 || maxLength > MAX_LENGTH_LIMIT)) {
            throw invalid("freeText.maxLength 는 1~" + MAX_LENGTH_LIMIT + " 이어야 합니다.");
        }
        if (maxLines != null && (maxLines < 1 || maxLines > MAX_LINES_LIMIT)) {
            throw invalid("freeText.maxLines 는 1~" + MAX_LINES_LIMIT + " 이어야 합니다.");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, message);
    }
}
