package com.harucut.media.compose;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.storage.util.S3Keys;
import org.springframework.stereotype.Component;

import java.util.List;

// 요청 시점의 Frame을 자기 완결 렌더 스펙으로 바꾼다 — Job에 스냅샷으로 저장될 값이다.
// 여기가 "이 프레임으로 합성이 가능한가"의 관문이기도 하다: 합성기는 S3에서만 가져오므로
// S3에 없는 참조(안 구운 텍스트, 외부 URL 자산)는 여기서 400으로 끊는다
@Component
public class ComposeSpecAssembler {

    public ComposeSpec assemble(Frame frame, String backgroundColorOverride) {
        FrameLayout layout = frame.getFrameType().getLayout();
        // 사용자 프레임이면 참조 자산이 전부 그 소유자의 것이어야 한다. 저장 시점에도 검사하지만
        // 검사가 생기기 전에 저장된 프레임(남의 스티커 key 등)이 합성으로 새지 않게 여기서 한 번 더 본다.
        // 시스템 프레임(user=null)은 ROLE_ADMIN 이 신뢰 경계라 검사하지 않는다
        String owner = frame.getUser() == null ? null : frame.getUser().getPublicId();
        return new ComposeSpec(
                layout.canvasWidth(), layout.canvasHeight(),
                composableBackground(frame.getBackground(), backgroundColorOverride, owner),
                layout.slots(),
                frame.getCellCutouts(),
                frame.getComponents().stream().map(component -> toLayer(component, owner)).toList());
    }

    private BackgroundAttributes composableBackground(
            BackgroundAttributes background, String colorOverride, String owner) {
        if (background instanceof BackgroundAttributes.Image image) {
            if (colorOverride != null) {
                // 이미지 배경에 색을 씌우는 건 프론트 미리보기에 없는 조합 — 조용히 무시하면
                // 사용자는 색이 반영됐다고 믿은 채 결과를 받는다. 명시적으로 끊는다
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                        "이미지 배경 프레임에는 배경색을 지정할 수 없다.");
            }
            if (!S3Keys.isManagedKey(image.key())) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                        "S3에 없는 배경 이미지는 합성할 수 없다.");
            }
            assertOwned(image.key(), owner);
            // 자산 public id 는 편집·응답용이다 — 합성기는 key 만 읽으므로 스냅샷에 남기지 않는다
            return image.withKey(image.key(), null);
        }
        return colorOverride == null
                ? background
                : new BackgroundAttributes.Color(colorOverride);
    }

    private ComposeSpec.Layer toLayer(FrameComponent component, String owner) {
        String source = switch (component.getType()) {
            case PHOTO, STICKER -> component.getSource();
            case TEXT -> component.getRenderedKey();
        };
        if (source == null || source.isBlank()) {
            // 1단계에서 미뤄둔 renderedKey 검증의 자리 — 안 구운 텍스트는 여기서 걸린다
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "구운 텍스트 이미지(renderedKey)가 없는 프레임은 합성할 수 없다.");
        }
        if (!S3Keys.isManagedKey(source)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "S3에 없는 자산은 합성할 수 없다: " + source);
        }
        assertOwned(source, owner);
        return new ComposeSpec.Layer(
                source,
                component.getX(), component.getY(),
                component.getWidth() == null ? 0.0 : component.getWidth(),
                component.getHeight() == null ? 0.0 : component.getHeight(),
                component.getScale() == null ? 1.0 : component.getScale(),
                component.getRotation(),
                component.getZIndex(),
                opacityOf(component));
    }

    private static void assertOwned(String key, String owner) {
        if (owner != null) {
            S3Keys.assertOwnedBy(key, owner);
        }
    }

    // 프론트 drawThemeOverlay와 같은 읽기: styleJson.opacity가 숫자면 0~1로 잘라 쓰고, 아니면 1
    private double opacityOf(FrameComponent component) {
        Object raw = component.getStyle().get("opacity");
        if (raw instanceof Number number) {
            return Math.min(1.0, Math.max(0.0, number.doubleValue()));
        }
        return 1.0;
    }
}
