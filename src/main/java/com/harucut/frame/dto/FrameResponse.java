package com.harucut.frame.dto;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "프레임")
public record FrameResponse(

        @Schema(description = "프레임 ID. 조회·수정·삭제에 쓴다", example = "1")
        Long frameId,

        @Schema(description = "제목", example = "봄 여행 4컷")
        String title,

        @Schema(description = "설명. 저장할 때 생략했으면 빈 문자열", example = "벚꽃 배경의 여행 프레임")
        String description,

        @Schema(description = """
                프리뷰 이미지의 **presigned GET URL (24시간)**. 요청에서 `previewKey` 였던 것이다.
                이름이 `previewKey` → `source` 로 바뀌는 데 주의.""",
                example = "https://harucut-bucket.s3.ap-northeast-2.amazonaws.com/uploads/.../preview.png?X-Amz-Signature=...")
        String source,

        @Schema(description = "프레임 종류", example = "CLASSIC")
        FrameType frameType,

        @Schema(description = """
                캔버스 너비. **`frameType` 에서 파생된 고정값**이라 저장된 값이 아니다.
                프론트의 `frameLayouts` 상수와 반드시 같아야 하고, 어긋나면 컴포넌트 좌표가 조용히 틀어진다.""",
                example = "2000")
        int canvasWidth,

        @Schema(description = "캔버스 높이. 위와 같다", example = "6000")
        int canvasHeight,

        @Schema(description = """
                배경. `COLOR` 면 `value`, `IMAGE` 면 `key`·`opacity` 에 더해
                **응답에만 있는 `url`**(presigned GET)이 실린다. 화면에 그릴 때는 `url` 을 쓰고,
                수정 요청을 만들 때는 `key` 를 그대로 되돌려준다.""")
        BackgroundAttributes background,

        @Schema(description = "칸별 누끼 토글 4개. 저장할 때 안 보냈으면 전부 false", example = "[true, false, false, true]")
        List<Boolean> cellCutouts,

        @Schema(description = "컴포넌트. **`zIndex` 오름차순으로 정렬돼 있다** — 순서대로 그리면 된다")
        List<ComponentResponse> components,

        @Schema(description = """
                기본 제공 프레임 여부. `true` 면 모든 사용자에게 보이고 요금제 제한을 받지 않는다.
                **사용자 API 로는 수정·삭제할 수 없다**(404).""",
                example = "false")
        boolean isSystem,

        @Schema(description = """
                낙관적 잠금 값. 다음 PUT 의 `version` 에 그대로 보낸다 — 그사이 다른 저장이 있었으면 409 `FRAME-002`.""",
                example = "3")
        Long version,

        @Schema(description = """
                프레임이 속한 공간의 public id. 조직 공간 프레임이면 그 공간이고, 시스템 프레임·공간이 없던 옛 프레임은 null(키 없음).""",
                example = "Ws1aB2cD3eF4")
        String workspaceId
) {
    // presign이 필요한 조립은 어셈블러 몫이고, 이 팩토리는 캔버스 파생만 강제한다 —
    // 캔버스에 다른 값을 넣는 코드를 쓸 수 없게 파라미터에서 뺐다
    public static FrameResponse of(Frame frame, String workspacePublicId, String previewUrl,
                                   BackgroundAttributes resolvedBackground,
                                   List<ComponentResponse> components) {
        return new FrameResponse(
                frame.getId(), frame.getTitle(), frame.getDescription(),
                previewUrl, frame.getFrameType(),
                frame.getFrameType().getLayout().canvasWidth(),
                frame.getFrameType().getLayout().canvasHeight(),
                resolvedBackground, frame.getCellCutouts(), components, frame.isSystem(), frame.getVersion(),
                workspacePublicId);
    }

    @Schema(description = "프레임 컴포넌트")
    public record ComponentResponse(

            @Schema(description = """
                    컴포넌트 ID. ⚠️ **수정할 때마다 바뀐다** — 수정은 전체 교체라 컴포넌트를 지우고 다시 만든다.
                    이 값을 저장해 두고 재사용하지 말 것.""",
                    example = "10")
            Long id,

            @Schema(description = "컴포넌트 종류", example = "PHOTO")
            ComponentType type,

            @Schema(description = """
                    화면에 그릴 값. `PHOTO` 와 uploads/ key 인 `STICKER` 는 presigned GET URL,
                    정적 경로 `STICKER` 는 경로 그대로, `TEXT` 는 글자 내용.""",
                    example = "https://harucut-bucket.s3.ap-northeast-2.amazonaws.com/uploads/.../photo1.png?X-Amz-Signature=...")
            String source,

            @Schema(description = """
                    정규화된 순수 S3 key(정적 스티커는 그 경로). **항상 채워진다.**
                    **수정 요청을 다시 만들 때 `source` 자리에 이 값을 넣는다**(PHOTO·STICKER 공통).
                    URL 이 아니라 key 를 되돌려줘야 하기 때문에 따로 있다.""",
                    example = "uploads/users/AbCdEf12Gh/components/photo1.png")
            String key,

            @Schema(description = "X 좌표", example = "120.5")
            double x,

            @Schema(description = "Y 좌표", example = "220.0")
            double y,

            @Schema(description = "너비. 저장 시 안 보냈으면 0.0", example = "360.0")
            double width,

            @Schema(description = "높이. 저장 시 안 보냈으면 0.0", example = "480.0")
            double height,

            @Schema(description = "배율. 저장 시 안 보냈으면 **1.0**(0.0 아님)", example = "1.0")
            double scale,

            @Schema(description = "회전 각도", example = "0.0")
            double rotation,

            @Schema(description = "겹침 순서. 목록은 이 값 오름차순", example = "1")
            int zIndex,

            @Schema(description = "요청의 `styleJson`. **이름이 다르다.** 없으면 빈 객체",
                    example = "{\"borderRadius\": 8}")
            Map<String, Object> style,

            @Schema(description = "편집기 잠금 상태(저장한 값). 인가 근거가 아니다", example = "false")
            boolean locked,

            @Schema(description = "PHOTO·STICKER 를 자산으로 저장했으면 그 자산 public id. 아니면 null", example = "As8kLm2Pq9Xz")
            String assetId,

            @Schema(description = "TEXT 의 구운 PNG 를 자산으로 저장했으면 그 자산 public id. 아니면 null")
            String renderedAssetId,

            @Schema(description = """
                    TEXT 의 구운 PNG key. 문구·스타일·크기가 저장 때와 같으면 다시 굽지 말고 재저장에 그대로 쓴다.
                    TEXT 가 아니거나 구운 PNG 가 없으면 null.""",
                    example = "uploads/users/AbCdEf12Gh/components/text1.png")
            String renderedKey,

            @Schema(description = "TEXT 의 구운 PNG presigned GET URL — 그리기 전용")
            String renderedUrl
    ) {
        // null 보정 규칙의 소유자 — "안 보냈다"는 기록(null)을 응답 계약(0.0/1.0)으로 번역한다
        public static ComponentResponse of(FrameComponent component, String resolvedSource,
                                           String renderedUrl, String assetPublicId,
                                           String renderedAssetPublicId) {
            return new ComponentResponse(
                    component.getId(), component.getType(), resolvedSource, component.getSource(),
                    component.getX(), component.getY(),
                    component.getWidth() == null ? 0.0 : component.getWidth(),
                    component.getHeight() == null ? 0.0 : component.getHeight(),
                    component.getScale() == null ? 1.0 : component.getScale(),
                    component.getRotation(), component.getZIndex(), component.getStyle(),
                    component.isLocked(), assetPublicId, renderedAssetPublicId,
                    component.getRenderedKey(), renderedUrl);
        }
    }
}
