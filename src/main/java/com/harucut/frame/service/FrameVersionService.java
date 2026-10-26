package com.harucut.frame.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.asset.service.AssetService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.entity.FrameVersionAsset;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.layout.FrameLayout;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.repository.FrameVersionAssetRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.frame.version.FrameSpecV2;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 프레임의 현재 상태를 불변 발행본(FrameSpec v2)으로 고정한다 (ADR-0006 §4).
 *
 * <p>발행본은 자산만 참조한다. key 문자열로만 저장된 예전 이미지는 발행할 때 자산으로 편입하고 같은 검증을 돌린다 —
 * 프레임 자체는 고치지 않는다(프레임 version 이 바뀌면 편집기의 다음 저장이 409 가 된다). 편입된 key 는 자산 행이
 * 생겼으므로 이후 프레임 정리에서 지워지지 않는다(FrameComponentAssembler.scheduleDeletion).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class FrameVersionService {

    private static final String LAYER_ROLE = "FOREGROUND";

    private final FrameRepository frameRepository;
    private final FrameVersionRepository frameVersionRepository;
    private final FrameVersionAssetRepository frameVersionAssetRepository;
    private final FrameService frameService;
    private final FrameComponentAssembler frameComponentAssembler;
    private final AssetService assetService;
    private final AssetRepository assetRepository;
    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    public FrameVersionResponse publish(String publicId, Long frameId) {
        User user = getUser(publicId);
        frameService.getOwnedReadableFrame(user, frameId);
        Frame frame = lock(frameId);
        Long workspaceId = frame.getWorkspaceId() != null
                ? frame.getWorkspaceId()
                : workspaceService.personalWorkspaceOf(user.getId()).getId();
        return doPublish(frame, new Publisher(user.getId(), user.getPublicId(), workspaceId));
    }

    // 시스템 프레임: 소유자·공간이 없다. 편입 자산은 SYSTEM 범위(workspace NULL)
    public FrameVersionResponse publishSystem(String adminPublicId, Long frameId) {
        User admin = getUser(adminPublicId);
        Frame frame = lock(frameId);
        if (!frame.isSystem()) {
            throw new BusinessException(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);
        }
        return doPublish(frame, new Publisher(admin.getId(), null, null));
    }

    @Transactional(readOnly = true)
    public List<FrameVersionResponse> list(String publicId, Long frameId) {
        frameService.getOwnedReadableFrame(getUser(publicId), frameId);
        return frameVersionRepository.findAllByFrameIdOrderByVersionNoDesc(frameId).stream()
                .map(FrameVersionResponse::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public FrameVersionResponse get(String publicId, Long frameId, int versionNo) {
        frameService.getOwnedReadableFrame(getUser(publicId), frameId);
        FrameVersion version = frameVersionRepository.findByFrameIdAndVersionNo(frameId, versionNo)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        return FrameVersionResponse.full(version, withUrls(objectMapper.readValue(version.getSpecJson(), FrameSpecV2.class)));
    }

    /**
     * 행사 참가자용 — 소유권을 묻지 않는다. 호출자가 "이 회차의 경험 버전이 이 발행본을 제공하는가"를 이미 확인했다.
     */
    @Transactional(readOnly = true)
    public FrameVersionResponse view(Long frameVersionId) {
        FrameVersion version = frameVersionRepository.findById(frameVersionId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        return FrameVersionResponse.full(version, withUrls(objectMapper.readValue(version.getSpecJson(), FrameSpecV2.class)));
    }

    private FrameVersionResponse doPublish(Frame frame, Publisher publisher) {
        frameComponentAssembler.assertComposable(frame);
        Map<Long, Asset> referenced = new LinkedHashMap<>();

        BackgroundAttributes background = frame.getBackground();
        if (background instanceof BackgroundAttributes.Image image) {
            Asset asset = image.assetId() != null
                    ? requireStored(assetRepository.findAllByPublicIdIn(List.of(image.assetId())).stream()
                            .findFirst().orElse(null), AssetPurpose.FRAME_ASSET)
                    : adopt(image.key(), AssetPurpose.FRAME_ASSET, publisher);
            referenced.put(asset.getId(), asset);
            background = image.withKey(asset.getS3Key(), asset.getPublicId()).withUrl(null);
        }

        Map<Long, Asset> stored = assetRepository.findAllById(frame.getComponents().stream()
                        .flatMap(c -> java.util.stream.Stream.of(c.getAssetId(), c.getRenderedAssetId()))
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));

        List<FrameSpecV2.Layer> layers = new ArrayList<>();
        for (FrameComponent component : frame.getComponents().stream()
                .sorted(Comparator.comparingInt(FrameComponent::getZIndex)).toList()) {
            Asset asset = layerAsset(component, stored, publisher);
            referenced.put(asset.getId(), asset);
            layers.add(new FrameSpecV2.Layer(
                    LAYER_ROLE,
                    component.getType() == ComponentType.TEXT ? "TEXT" : "IMAGE",
                    asset.getPublicId(), null,
                    component.getX(), component.getY(),
                    component.getWidth() == null ? 0.0 : component.getWidth(),
                    component.getHeight() == null ? 0.0 : component.getHeight(),
                    component.getScale() == null ? 1.0 : component.getScale(),
                    component.getRotation(), component.getZIndex(), opacityOf(component),
                    component.getType() == ComponentType.TEXT
                            ? new FrameSpecV2.Text(component.getSource(), component.getStyle())
                            : null));
        }

        FrameLayout layout = frame.getFrameType().getLayout();
        List<FrameSpecV2.Slot> slots = IntStream.range(0, layout.slots().size())
                .mapToObj(i -> {
                    FrameLayout.Slot slot = layout.slots().get(i);
                    return new FrameSpecV2.Slot("s" + (i + 1), slot.x(), slot.y(), slot.width(), slot.height());
                })
                .toList();
        FrameSpecV2 spec = new FrameSpecV2(
                FrameSpecV2.SCHEMA_VERSION,
                new FrameSpecV2.Canvas(layout.canvasWidth(), layout.canvasHeight()),
                background,
                new FrameSpecV2.Layout(frame.getFrameType(), slots),
                frame.getCellCutouts(),
                IntStream.range(0, slots.size()).mapToObj(i -> new FrameSpecV2.SlotBinding(slots.get(i).slotId(), i)).toList(),
                layers,
                FrameSpecV2.EditPolicy.FIXED);

        int versionNo = frameVersionRepository.findMaxVersionNo(frame.getId()) + 1;
        FrameVersion version = frameVersionRepository.save(FrameVersion.publish(
                frame.getId(), versionNo, frame.getFrameType(), objectMapper.writeValueAsString(spec), publisher.userId()));
        referenced.keySet().forEach(assetId ->
                frameVersionAssetRepository.save(new FrameVersionAsset(version.getId(), assetId)));
        return FrameVersionResponse.full(version, withUrls(spec, referenced.values()));
    }

    private Asset layerAsset(FrameComponent component, Map<Long, Asset> stored, Publisher publisher) {
        if (component.getType() == ComponentType.TEXT) {
            if (component.getRenderedKey() == null || component.getRenderedKey().isBlank()) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                        "구운 텍스트 이미지(renderedKey)가 없는 프레임은 발행할 수 없다.");
            }
            return component.getRenderedAssetId() != null
                    ? requireStored(stored.get(component.getRenderedAssetId()), AssetPurpose.TEXT_RENDER)
                    : adopt(component.getRenderedKey(), AssetPurpose.TEXT_RENDER, publisher);
        }
        if (component.getAssetId() != null) {
            return requireStored(stored.get(component.getAssetId()), AssetPurpose.FRAME_ASSET);
        }
        if (!S3Keys.isManagedKey(component.getSource())) {
            // 정적 스티커 경로·외부 URL — 합성기가 읽을 수 없다(합성 API 와 같은 규칙)
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "S3에 없는 자산은 발행할 수 없다: " + component.getSource());
        }
        return adopt(component.getSource(), AssetPurpose.FRAME_ASSET, publisher);
    }

    // 예전 key 를 자산으로 편입. 사용자 프레임이면 그 사용자 폴더의 key 여야 한다(저장·합성과 같은 소유권 규칙)
    private Asset adopt(String key, AssetPurpose purpose, Publisher publisher) {
        if (publisher.ownerPublicId() != null) {
            S3Keys.assertOwnedBy(key, publisher.ownerPublicId());
        }
        AssetService.AdoptResult result = assetService.adopt(key, purpose, publisher.workspaceId(), publisher.userId());
        if (result.rejection() != null) {
            throw new BusinessException(FrameErrorCode.ASSET_NOT_USABLE,
                    "자산 편입 실패 " + key + ": " + result.rejection().getCode());
        }
        return result.asset();
    }

    // 저장 시점에 이미 검사한 자산 참조 — 그사이 상태가 바뀌었을 수 있어 검증 상태와 용도만 다시 본다
    private static Asset requireStored(Asset asset, AssetPurpose purpose) {
        if (asset == null || !asset.isVerified() || asset.getPurpose() != purpose) {
            throw new BusinessException(FrameErrorCode.ASSET_NOT_USABLE, "발행할 수 없는 자산 참조");
        }
        return asset;
    }

    private FrameSpecV2 withUrls(FrameSpecV2 spec) {
        List<String> publicIds = new ArrayList<>();
        if (spec.background() instanceof BackgroundAttributes.Image image && image.assetId() != null) {
            publicIds.add(image.assetId());
        }
        spec.layers().forEach(layer -> publicIds.add(layer.assetId()));
        return withUrls(spec, assetRepository.findAllByPublicIdIn(publicIds));
    }

    private FrameSpecV2 withUrls(FrameSpecV2 spec, java.util.Collection<Asset> assets) {
        Map<String, String> keyByPublicId = assets.stream()
                .collect(Collectors.toMap(Asset::getPublicId, Asset::getS3Key, (a, b) -> a));
        BackgroundAttributes background = spec.background() instanceof BackgroundAttributes.Image image
                ? image.withUrl(presign(image.key()))
                : spec.background();
        List<FrameSpecV2.Layer> layers = spec.layers().stream()
                .map(layer -> layer.withUrl(presign(keyByPublicId.get(layer.assetId()))))
                .toList();
        return spec.withUrls(background, layers);
    }

    private String presign(String key) {
        return key == null ? null : fileStorageService.generatePresignedGetUrl(key, storageProperties.download().getTtl());
    }

    // ComposeSpecAssembler 와 같은 읽기: styleJson.opacity 가 숫자면 0~1 로 자르고, 아니면 1
    private static double opacityOf(FrameComponent component) {
        Object raw = component.getStyle().get("opacity");
        if (raw instanceof Number number) {
            return Math.min(1.0, Math.max(0.0, number.doubleValue()));
        }
        return 1.0;
    }

    private Frame lock(Long frameId) {
        return frameRepository.findByIdForUpdate(frameId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    // 발행 주체. 시스템 프레임이면 ownerPublicId·workspaceId 가 null
    private record Publisher(Long userId, String ownerPublicId, Long workspaceId) {
    }
}
