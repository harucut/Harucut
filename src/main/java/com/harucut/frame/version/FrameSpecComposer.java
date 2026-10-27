package com.harucut.frame.version;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.cell.PhotoCells;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.media.compose.ComposeSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 발행본(FrameSpec v2) → 합성기 입력(ComposeSpec). 행사 합성은 프레임의 현재 상태가 아니라 이 발행본만 쓴다 —
 * 클라이언트는 frameType·spec·배경색을 보낼 수 없다 (ADR-0006 §4, ADR-0007 §0).
 *
 * <p>레이어의 assetId 를 실제 key 로 바꾼다. 발행 때 VERIFIED 였던 자산만 들어 있으므로 여기서는 존재만 본다.
 */
@Component
@RequiredArgsConstructor
public class FrameSpecComposer {

    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper;

    public ComposeSpec compose(FrameVersion version) {
        FrameSpecV2 spec = objectMapper.readValue(version.getSpecJson(), FrameSpecV2.class);
        Map<String, String> keys = assetRepository.findAllByPublicIdIn(
                        spec.layers().stream().map(FrameSpecV2.Layer::assetId).toList()).stream()
                .collect(Collectors.toMap(Asset::getPublicId, Asset::getS3Key, (a, b) -> a));

        List<ComposeSpec.Layer> layers = spec.layers().stream()
                .map(layer -> new ComposeSpec.Layer(requireKey(keys, layer.assetId()),
                        layer.x(), layer.y(), layer.width(), layer.height(), layer.scale(),
                        layer.rotation(), layer.zIndex(), layer.opacity()))
                .toList();
        List<FrameLayout.Slot> slots = spec.layout().slots().stream()
                .map(slot -> new FrameLayout.Slot(slot.x(), slot.y(), slot.width(), slot.height()))
                .toList();
        // 합성기는 key 만 읽는다 — 자산 public id 는 스냅샷에 남기지 않는다
        BackgroundAttributes background = spec.background() instanceof BackgroundAttributes.Image image
                ? image.withKey(image.key(), null).withUrl(null)
                : spec.background();
        // 자유 사진 칸(ADR-0012)이면 칸마다의 회전·모양·순번 — slots 는 발행본이 이미 칸 사각형으로 채워 두었다
        List<ComposeSpec.Cell> cells = spec.layout().cells() == null ? null : spec.layout().cells().stream()
                .map(cell -> PhotoCells.composeCell(cell.rotation(), cell.shape(), cell.shotIndex()))
                .toList();
        return new ComposeSpec(spec.canvas().width(), spec.canvas().height(), background, slots,
                spec.cellCutouts(), layers, cells);
    }

    private static String requireKey(Map<String, String> keys, String assetId) {
        String key = keys.get(assetId);
        if (key == null) {
            throw new BusinessException(FrameErrorCode.ASSET_NOT_USABLE, "발행본 자산이 없다: " + assetId);
        }
        return key;
    }
}
