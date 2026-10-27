package com.harucut.event.dto;

import com.harucut.event.enums.EventKind;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 행사 설정(초안). 만들 때는 name·slug 만 필수이고, 수정(PATCH)은 보낸 필드만 바꾼다.
 * 허용 레이아웃·프레임·동의 항목·테마는 게시할 때 경험 발행본으로 굳는다 (ADR-0007 §4).
 */
@Schema(description = "행사 설정")
public record EventSettings(
        @Schema(description = "행사명", example = "민지 ♥ 준호 결혼식")
        String name,

        @Schema(description = "입장 주소 /e/{slug}. 영문 소문자·숫자·하이픈 3~40자. **게시 후에는 바꿀 수 없다**",
                example = "wedding-1027")
        String slug,

        @Schema(description = "촬영 시작 시각(없으면 게시 즉시)")
        OffsetDateTime captureStartsAt,

        @Schema(description = "촬영 종료 시각(없으면 상시형 — close-capture 로 끝낸다)")
        OffsetDateTime captureEndsAt,

        @Schema(description = "표시용 시간대", example = "Asia/Seoul")
        String timezone,

        @Schema(description = "개인 휴대폰 링크·QR 참가 허용", example = "true")
        Boolean byodEnabled,

        @Schema(description = "현장 부스 참가 허용", example = "false")
        Boolean boothEnabled,

        @Schema(description = "결과 보관 일수 — 결과 링크·재다운로드가 이 기간 동안 열린다", example = "30")
        Integer resultRetentionDays,

        @Schema(description = "허용 레이아웃(기존 네 종류의 부분집합)")
        List<FrameType> allowedLayouts,

        @Schema(description = "제공할 프레임 발행본 — 허용 레이아웃마다 하나 이상, 기본값은 전체에서 정확히 하나")
        List<FrameRef> frames,

        @Schema(description = "참가 동의 항목 — 사진 수신 주체·목적·보관 안내를 담는다. 하나 이상")
        List<ConsentItem> consentItems,

        @Schema(description = "페이지 테마 — 게시 때 형식·자산·이용권 기능을 검사한다") PageTheme theme,

        @Schema(description = "프로젝트 public id(선택) — 같은 공간의 보관되지 않은 프로젝트. 게시 뒤에는 바꿀 수 없다(PROJECT-002). "
                + "PATCH 에서 null 이면 프로젝트에서 뺀다", example = "Pj3kL9mN2qRs") String projectId,

        @Schema(description = "행사 종류 — SCHEDULED(정해진 기간, 기본)·ALWAYS_ON(상시 캠페인: 종료 시각을 두지 않고 close-capture 로 끝낸다). "
                + "게시 전 초안에서만 바꿀 수 있다(게시 뒤 400 GEN-002). 상속하지 않는다", example = "SCHEDULED") EventKind kind,

        @Schema(description = "참가자 자유 문구(R5 D06) — {enabled:false} 는 끔, null 은 위 단계 값을 따른다(모두 비면 끔). 객체째 대체") FreeTextPolicy freeText
) {

    public EventSettings(String name, String slug, OffsetDateTime captureStartsAt, OffsetDateTime captureEndsAt,
                         String timezone, Boolean byodEnabled, Boolean boothEnabled, Integer resultRetentionDays,
                         List<FrameType> allowedLayouts, List<FrameRef> frames, List<ConsentItem> consentItems,
                         PageTheme theme, String projectId, EventKind kind) {
        this(name, slug, captureStartsAt, captureEndsAt, timezone, byodEnabled, boothEnabled, resultRetentionDays,
                allowedLayouts, frames, consentItems, theme, projectId, kind, null);
    }

    @Schema(description = "제공 프레임 발행본")
    public record FrameRef(
            @Schema(description = "프레임 발행본 ID", example = "41") Long frameVersionId,
            @Schema(description = "이 발행본의 레이아웃 — 발행본의 frameType 과 같아야 한다", example = "CLASSIC") FrameType layoutType,
            @Schema(description = "표시 순서", example = "1") Integer sortOrder,
            @Schema(description = "기본 선택", example = "true") Boolean isDefault
    ) {
    }

    @Schema(description = "참가 동의 항목")
    public record ConsentItem(
            @Schema(description = "항목 코드", example = "photo-collection") String code,
            @Schema(description = "항목 버전 — 문구를 바꾸면 올린다", example = "1") Integer version,
            @Schema(description = "제목", example = "사진 수집·이용 동의") String title,
            @Schema(description = "필수 여부", example = "true") Boolean required,
            @Schema(description = "본문 — 사진을 받는 주체·목적·보관 기간") String body
    ) {
    }
}
