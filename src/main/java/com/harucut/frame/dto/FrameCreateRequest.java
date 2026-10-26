package com.harucut.frame.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

// 생성(POST)과 수정(PUT)이 같은 본문을 쓴다
@Schema(description = "프레임 생성·수정 요청. **수정도 전체 교체다** — 보낸 내용이 그대로 최종 상태가 된다")
public record FrameCreateRequest(

        @NotBlank
        @Schema(description = "프레임 제목", example = "봄 여행 4컷", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(description = "설명. 생략하면 빈 문자열로 저장된다", example = "벚꽃 배경의 여행 프레임")
        String description,

        @NotBlank
        @Schema(description = "프리뷰 이미지의 S3 key. 업로드 API 에 `type: FRAME` 으로 올린 뒤 받은 key",
                example = "uploads/users/AbCdEf12Gh/frames/550e8400.png",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String previewKey,

        @NotNull
        @Schema(description = "프레임 종류. **캔버스 크기가 이 값에서 파생된다** — 별도로 저장하지 않는다",
                example = "CLASSIC", requiredMode = Schema.RequiredMode.REQUIRED)
        FrameType frameType,

        @Schema(description = "⚠️ **무시된다.** 캔버스 크기는 `frameType` 에서 계산한다. 하위 호환으로 받기만 한다",
                example = "2000")
        Integer canvasWidth,

        @Schema(description = "⚠️ **무시된다.** 위와 같다", example = "6000")
        Integer canvasHeight,

        @NotNull
        @Schema(description = """
                배경. `type` 에 따라 모양이 갈린다.

                `{ "type": "COLOR", "value": "#FFE4E1" }` ·
                `{ "type": "IMAGE", "key": "uploads/users/.../bg.png", "opacity": 0.8 }`

                **이 둘뿐이다.** `GRADIENT` 같은 다른 값을 보내면 역직렬화가 깨져 `GEN-006` 이다.
                IMAGE 의 `key` 는 presigned URL 을 통째로 보내도 서버가 key 로 정규화한다.
                응답에만 나오는 `url` 필드는 보내도 무시된다.""",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BackgroundAttributes background,

        @Size(min = 4, max = 4)
        @Schema(description = """
                칸별 누끼 토글. **보내려면 정확히 4개**여야 하고, 촬영 슬롯 순서다.
                생략하면 전부 끈 것으로 본다.

                ⚠️ **서버는 이 값으로 아무것도 그리지 않는다** — 누끼(배경 제거 + 검은 배경)는
                프론트가 원본 픽셀에 구워서 업로드해야 한다. 이 토글은 편집기가 저장 프레임을
                다시 열 때 어느 칸이 누끼인지 복원하는 용도다.""",
                example = "[true, false, false, true]")
        List<Boolean> cellCutouts,

        @Valid
        @Schema(description = "프레임 위에 얹히는 요소들. 생략하거나 빈 배열이어도 된다")
        List<ComponentRequest> components,

        @Schema(description = """
                낙관적 잠금(PUT 에서만 의미가 있다). 마지막으로 받은 응답의 `version` 을 그대로 보낸다.
                서버 값과 다르면 **409 `FRAME-002`** 이고 `data.currentVersion` 에 서버 값이 실린다.
                **생략하면 지금처럼 덮어쓴다**(기존 클라이언트 호환).""",
                example = "3")
        Long version
) {
    // version 이 없던 시절의 생성자 — 기존 호출부·테스트 호환
    public FrameCreateRequest(String title, String description, String previewKey, FrameType frameType,
                              Integer canvasWidth, Integer canvasHeight, BackgroundAttributes background,
                              List<Boolean> cellCutouts, List<ComponentRequest> components) {
        this(title, description, previewKey, frameType, canvasWidth, canvasHeight, background,
                cellCutouts, components, null);
    }

    // "description 없으면 빈 문자열로 저장" 규칙의 소유자
    public String descriptionOrEmpty() {
        return description == null ? "" : description;
    }

    @Schema(description = "프레임 컴포넌트 (사진·스티커·텍스트)")
    public record ComponentRequest(

            @Schema(description = "⚠️ **저장하지 않는다.** 클라이언트가 쓰는 임시 식별자", example = "comp-1")
            String id,

            @NotNull
            @Schema(description = "`PHOTO` 사용자 사진 · `STICKER` 데코레이션 · `TEXT` 글자",
                    example = "PHOTO", requiredMode = Schema.RequiredMode.REQUIRED)
            ComponentType type,

            @NotBlank
            @Schema(description = """
                    타입에 따라 의미가 다르다.
                    `PHOTO` 업로드한 이미지의 S3 key · `STICKER` 스티커 경로 · `TEXT` **글자 내용 그 자체**""",
                    example = "uploads/users/AbCdEf12Gh/components/photo1.png",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String source,

            @Schema(description = """
                    **TEXT 전용.** 글자 층을 투명 PNG 로 구워 올린 S3 key.
                    서버는 글자를 그리지 않는다 — 브라우저와 서버가 폰트를 다르게 그려 편집 화면과
                    결과물이 어긋나는 걸 막으려고, 사용자가 본 픽셀을 그대로 쓴다.

                    저장 시점에는 선택이지만 **없으면 그 프레임으로 네컷 합성이 실패한다.**
                    글자나 스타일을 바꿨으면 **반드시 다시 구워 새 key를 보낼 것** — 서버는 어긋남을 감지하지 못한다.
                    응답에는 나가지 않는다(합성 전용).""",
                    example = "uploads/users/AbCdEf12Gh/components/text-layer.png")
            String renderedKey,

            @Schema(description = "X 좌표. 생략하면 0", example = "120.5")
            Double x,

            @Schema(description = "Y 좌표. 생략하면 0", example = "220.0")
            Double y,

            @Schema(description = "너비. **생략하면 응답에서 0.0 으로 나온다**", example = "360.0")
            Double width,

            @Schema(description = "높이. **생략하면 응답에서 0.0 으로 나온다**", example = "480.0")
            Double height,

            @Schema(description = "배율. **생략하면 응답에서 1.0 으로 나온다**", example = "1.0")
            Double scale,

            @Schema(description = "회전 각도. 생략하면 0", example = "0.0")
            Double rotation,

            @JsonAlias("zindex")
            @Schema(description = "겹침 순서. 생략하면 0. **소문자 `zindex` 로 보내도 받는다**(하위 호환)",
                    example = "1")
            Integer zIndex,

            @Schema(description = "자유 형식 스타일 맵. 응답에서는 `style` 이라는 이름으로 나온다",
                    example = "{\"borderRadius\": 8}")
            Map<String, Object> styleJson,

            @Schema(description = """
                    PHOTO·STICKER 의 이미지를 자산(`FRAME_ASSET`, VERIFIED)으로 지정한다.
                    있으면 `source` 대신 이 자산의 key 를 저장한다(`source` 는 여전히 보낸다).
                    남의 공간·미검증·용도 불일치면 400 `FRAME-005`.""",
                    example = "As8kLm2Pq9Xz")
            String assetId,

            @Schema(description = """
                    TEXT 의 구운 PNG 를 자산(`TEXT_RENDER`, VERIFIED)으로 지정한다. 있으면 `renderedKey` 대신.
                    `renderedKey` 로 보내도 그 key 가 자산이면 같은 검사를 한다.""",
                    example = "As3nQ7wE1tYu")
            String renderedAssetId,

            @Schema(description = "편집기 잠금. 저장·재열기용이며 인가 근거가 아니다. 생략하면 false", example = "false")
            Boolean locked
    ) {
        // 자산 참조·잠금이 없던 시절의 생성자 — 기존 호출부·테스트 호환
        public ComponentRequest(String id, ComponentType type, String source, String renderedKey,
                                Double x, Double y, Double width, Double height, Double scale,
                                Double rotation, Integer zIndex, Map<String, Object> styleJson) {
            this(id, type, source, renderedKey, x, y, width, height, scale, rotation, zIndex, styleJson,
                    null, null, null);
        }

        // Jackson 3는 원시형에 누락/null을 채워주지 않는다 (FAIL_ON_NULL_FOR_PRIMITIVES 기본 on).
        // "안 보내면 0"이라는 계약을 언어 기본값에 기대지 않고 여기서 명시적으로 소유한다.
        // width/height/scale은 의도적으로 제외 — null("안 보냈다")을 보존하고 보정은 응답 조립에서 한다
        public ComponentRequest {
            x = x == null ? 0.0 : x;
            y = y == null ? 0.0 : y;
            rotation = rotation == null ? 0.0 : rotation;
            zIndex = zIndex == null ? 0 : zIndex;
            locked = locked != null && locked;
        }
    }
}
