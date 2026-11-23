package com.harucut.media.compose;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.cell.PhotoCell;
import com.harucut.frame.cell.PhotoCells;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.storage.util.S3Keys;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 요청 시점의 Frame을 자기 완결 렌더 스펙으로 바꾼다 — Job에 스냅샷으로 저장될 값이다.
// 여기가 "이 프레임으로 합성이 가능한가"의 관문이기도 하다: 합성기는 S3에서만 가져오므로
// S3에 없는 참조(안 구운 텍스트, 외부 URL 자산)는 여기서 400으로 끊는다
@Component
@RequiredArgsConstructor
public class ComposeSpecAssembler {

    private final WorkspaceService workspaceService;

    public ComposeSpec assemble(Frame frame, String backgroundColorOverride) {
        FrameLayout layout = frame.getFrameType().getLayout();
        // 개인 프레임이면 예전 key(자산 참조가 아닌 key)가 그 소유자 폴더의 것이어야 한다. 저장 시점에도 검사하지만
        // 검사가 생기기 전에 저장된 프레임(남의 스티커 key 등)이 합성으로 새지 않게 여기서 한 번 더 본다 — 발행과 같은 규칙.
        // 조직 공간 프레임은 여러 멤버의 이미지가 섞인다 — 저장 소유권 검사가 생긴 뒤에 생겨 key 가 전부 저장 때 검사를 통과했다.
        // 시스템 프레임(user=null)은 ROLE_ADMIN 이 신뢰 경계라 검사하지 않는다
        String owner = frame.getUser() == null || workspaceService.organization(frame.getWorkspaceId()).isPresent()
                ? null : frame.getUser().getPublicId();
        // 자유 사진 칸(ADR-0012)이면 칸 사각형과 칸마다의 회전·모양·순번을 스냅샷에 싣는다
        List<PhotoCell> cells = frame.getPhotoCells();
        return new ComposeSpec(
                layout.canvasWidth(), layout.canvasHeight(),
                composableBackground(frame.getBackground(), backgroundColorOverride, owner),
                cells == null ? layout.slots() : PhotoCells.slots(cells),
                frame.getCellCutouts(),
                frame.getComponents().stream().map(component -> toLayer(component, owner)).toList(),
                cells == null ? null : PhotoCells.composeCells(cells));
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
            if (image.assetId() == null) {
                assertOwned(image.key(), owner);
            }
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
        Long assetId = component.getType() == ComponentType.TEXT ? component.getRenderedAssetId() : component.getAssetId();
        if (assetId == null) {
            assertOwned(source, owner);
        }
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

    // 자산 참조(assetId·renderedAssetId·배경 assetId)는 부르지 않는다 — 저장 때 FrameAssetResolver 가 공간 멤버십까지 봤다
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
