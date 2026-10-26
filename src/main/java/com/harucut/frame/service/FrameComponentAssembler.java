package com.harucut.frame.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.common.exception.BusinessException;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.config.FrameLimitsProperties;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

// 사용자 프레임 서비스와 관리자 시스템 프레임 서비스가 공통으로 쓰는
// 요청→엔티티 조립, 엔티티→응답 변환기. 두 서비스는 서로가 아닌 이것에 의존한다
@Component
@RequiredArgsConstructor
public class FrameComponentAssembler {

    private final FrameAssetManager frameAssetManager;
    private final FrameAssetResolver frameAssetResolver;
    private final FrameLimitsProperties limits;
    private final ObjectMapper objectMapper;

    /**
     * 요청 → 엔티티. 저장 직전에 순수 key로 정규화된다 — PHOTO·STICKER 의 source, TEXT의 renderedKey.
     * assetId·renderedAssetId 가 있으면 그 자산의 key 를 쓴다(FRAME-005 검사 포함).
     * ownerPublicId·ownerUserId 가 null 이면 시스템 프레임이다 — 소유권 검사를 건너뛴다.
     */
    public List<FrameComponent> createComponents(List<FrameCreateRequest.ComponentRequest> requests,
                                                 String ownerPublicId, Long ownerUserId) {
        return createComponents(requests, KeyCheck.owner(ownerPublicId), ownerUserId);
    }

    private List<FrameComponent> createComponents(List<FrameCreateRequest.ComponentRequest> requests,
                                                  KeyCheck keys, Long ownerUserId) {
        if (requests == null) {
            return List.of();
        }
        if (requests.size() > limits.maxLayers()) {
            throw new BusinessException(FrameErrorCode.TOO_MANY_LAYERS,
                    "레이어 " + requests.size() + "개 > 상한 " + limits.maxLayers());
        }
        return requests.stream()
                .map(dto -> toComponent(dto, keys, ownerUserId))
                .toList();
    }

    private FrameComponent toComponent(FrameCreateRequest.ComponentRequest dto,
                                       KeyCheck keys, Long ownerUserId) {
        Asset sourceAsset = dto.assetId() == null || dto.type() == ComponentType.TEXT
                ? null
                : frameAssetResolver.require(dto.assetId(), AssetPurpose.FRAME_ASSET, ownerUserId);
        Asset renderedAsset = renderedAssetFor(dto, ownerUserId);

        String source = sourceAsset != null
                ? sourceAsset.getS3Key()
                : source(dto.type(), dto.source(), keys);
        String renderedKey = renderedAsset != null
                ? renderedAsset.getS3Key()
                : renderedKeyFor(dto, keys);
        return FrameComponent.builder()
                .source(source)
                .type(dto.type())
                .x(dto.x()).y(dto.y())
                .width(dto.width()).height(dto.height()).scale(dto.scale())
                .rotation(dto.rotation())
                .zIndex(dto.zIndex())
                .renderedKey(renderedKey)
                .style(dto.styleJson())
                .locked(dto.locked())
                .assetId(sourceAsset == null ? null : sourceAsset.getId())
                .renderedAssetId(renderedAsset == null ? null : renderedAsset.getId())
                .build();
    }

    // TEXT 의 구운 PNG 자산: renderedAssetId 가 있으면 그것, 없으면 renderedKey 가 자산 key 인지 본다.
    // 자산 행이 없는 key 는 예전 업로드 경로라 기존 소유권 검사(normalizeImageKey)에 맡긴다
    private Asset renderedAssetFor(FrameCreateRequest.ComponentRequest dto, Long ownerUserId) {
        if (dto.type() != ComponentType.TEXT) {
            return null;
        }
        if (dto.renderedAssetId() != null) {
            return frameAssetResolver.require(dto.renderedAssetId(), AssetPurpose.TEXT_RENDER, ownerUserId);
        }
        if (dto.renderedKey() == null || dto.renderedKey().isBlank()) {
            return null;
        }
        String key = frameAssetManager.normalizeImageKey(dto.renderedKey(), null);
        return frameAssetResolver.findByKey(key, AssetPurpose.TEXT_RENDER, ownerUserId).orElse(null);
    }

    // 구운 텍스트 key는 TEXT에만 의미가 있다 — 다른 타입이 보내면 버려서 쓰레기 저장을 막는다.
    // TEXT라도 선택 값: 없으면 null로 두고, 있어야 하는지는 합성 API가 검증한다
    private String renderedKeyFor(FrameCreateRequest.ComponentRequest dto, KeyCheck keys) {
        if (dto.type() != ComponentType.TEXT
                || dto.renderedKey() == null || dto.renderedKey().isBlank()) {
            return null;
        }
        return image(dto.renderedKey(), keys);
    }

    // 이미지 key(배경·프리뷰·구운 텍스트). 소유권 판정은 FrameAssetManager 가 한 곳에서 한다 —
    // 여기서는 이 저장이 보증하는 key(이미 쓰던 key·공간 자산)일 때만 그 검사를 건너뛴다
    private String image(String pathOrKey, KeyCheck keys) {
        String normalized = frameAssetManager.normalizeImageKey(pathOrKey, null);
        return keys.vouches(normalized, frameAssetResolver) ? normalized
                : frameAssetManager.normalizeImageKey(pathOrKey, keys.ownerPublicId());
    }

    private String source(ComponentType type, String source, KeyCheck keys) {
        String value = frameAssetManager.normalizeSource(type, source, null);
        return type == ComponentType.TEXT || keys.vouches(S3Keys.normalizeManagedKey(value), frameAssetResolver) ? value
                : frameAssetManager.normalizeSource(type, source, keys.ownerPublicId());
    }

    /**
     * 이 저장에서 uploads/ key 를 받아도 되는가. ownerPublicId 가 null 이면 시스템 프레임 — 검사하지 않는다.
     * 그 밖에는 내 폴더의 key 이거나, 이 프레임이 이미 쓰던 key(저장 때 검사를 통과했다)이거나,
     * 프레임 공간의 검증된 자산 key 여야 한다. 뒤의 둘은 조직 공간에서 여러 멤버가 한 프레임을 고칠 때 필요하다 —
     * 다른 멤버가 넣은 이미지를 다시 저장할 때마다 403 이 나면 안 된다.
     */
    private record KeyCheck(String ownerPublicId, Set<String> carried, Long workspaceId) {
        static final KeyCheck NONE = new KeyCheck(null, Set.of(), null);

        static KeyCheck owner(String ownerPublicId) {
            return ownerPublicId == null ? NONE : new KeyCheck(ownerPublicId, Set.of(), null);
        }

        // 검사 없이 받아도 되는 key 인가 — 시스템 프레임은 소유자 검사 자체가 없어(null) 여기서 따질 필요가 없다
        boolean vouches(String key, FrameAssetResolver resolver) {
            return ownerPublicId != null && key != null && S3Keys.isManagedKey(key)
                    && (carried.contains(key) || resolver.isWorkspaceAsset(key, workspaceId));
        }
    }

    /**
     * 수정/삭제 시 즉시 지울 key — 자산 테이블 없이 key 문자열로 들어온 것만.
     * PHOTO·STICKER 의 source(관리 대상 key 만 실제로 지워진다 — 정적 스티커 경로는 S3Deleter 가 거른다)와
     * TEXT 의 renderedKey. 자산(assetId)으로 들어온 것은 초안·발행본도 참조할 수 있어 여기서 지우지 않는다 —
     * 미참조 자산 정리 배치의 몫이다(ADR-0006 §2).
     */
    public List<String> extractAssetKeys(List<FrameComponent> components) {
        List<String> keys = new ArrayList<>();
        for (FrameComponent component : components) {
            if (component.getType() != ComponentType.TEXT && component.getAssetId() == null) {
                keys.add(component.getSource());
            }
            if (component.getRenderedKey() != null && component.getRenderedAssetId() == null) {
                keys.add(component.getRenderedKey());
            }
        }
        return keys;
    }

    // 저장 직전 배경 정규화 — IMAGE key의 URL 흔적을 지우고, 응답 전용 url은 확실히 비운다.
    // assetId 가 있으면 그 자산(FRAME_ASSET)의 key 로 바꾼다
    public BackgroundAttributes normalizeBackground(BackgroundAttributes background,
                                                    String ownerPublicId, Long ownerUserId) {
        return normalizeBackground(background, KeyCheck.owner(ownerPublicId), ownerUserId);
    }

    private BackgroundAttributes normalizeBackground(BackgroundAttributes background, KeyCheck keys, Long ownerUserId) {
        return switch (background) {
            case BackgroundAttributes.Image image when image.assetId() != null -> {
                Asset asset = frameAssetResolver.require(image.assetId(), AssetPurpose.FRAME_ASSET, ownerUserId);
                yield image.withKey(asset.getS3Key(), asset.getPublicId()).withUrl(null);
            }
            case BackgroundAttributes.Image image -> image
                    .withKey(image(image.key(), keys), null)
                    .withUrl(null);
            case BackgroundAttributes.Color color -> color;
        };
    }

    // 수정/삭제 시 즉시 지울 배경 key — COLOR 와 자산으로 받은 배경은 없다(자산은 정리 배치 몫)
    public String extractBackgroundKey(BackgroundAttributes background) {
        return switch (background) {
            case BackgroundAttributes.Image image -> image.assetId() == null ? image.key() : null;
            case BackgroundAttributes.Color color -> null;
        };
    }

    // 합성 스냅샷(compose_job.spec_json)에 담길 크기를 저장 시점에 미리 잰다. 레이어는 합성기가 받는 모양
    // 그대로(ComposeSpec.Layer) 직렬화한다 — 합성 시점에 DB 오류(500)로 죽는 대신 여기서 400 으로 끊는다
    // 발행 직전 재검사용 — 상한이 생기기 전에 저장된 프레임도 발행본으로 굳히기 전에 걸러진다
    void assertComposable(Frame frame) {
        assertComposable(frame.getBackground(), frame.getComponents());
    }

    private void assertComposable(BackgroundAttributes background, List<FrameComponent> components) {
        List<ComposeSpec.Layer> layers = components.stream()
                .map(c -> new ComposeSpec.Layer(
                        Objects.requireNonNullElse(c.getType() == ComponentType.TEXT ? c.getRenderedKey() : c.getSource(), ""),
                        c.getX(), c.getY(),
                        c.getWidth() == null ? 0.0 : c.getWidth(),
                        c.getHeight() == null ? 0.0 : c.getHeight(),
                        c.getScale() == null ? 1.0 : c.getScale(),
                        c.getRotation(), c.getZIndex(), 1.0))
                .toList();
        int chars = objectMapper.writeValueAsString(Map.of("background", background, "layers", layers)).length();
        if (chars > limits.maxSpecChars()) {
            throw new BusinessException(FrameErrorCode.SPEC_TOO_LARGE,
                    "합성 스펙 약 " + chars + "자 > 상한 " + limits.maxSpecChars());
        }
    }

    // ── 생성 조립 — "DB에 URL을 저장하지 않는다"는 정규화 규칙을 서비스가 아니라 여기서 강제한다 ──

    public Frame assembleOwned(User user, FrameCreateRequest request) {
        return assembleOwned(user, request, null);
    }

    // workspaceId: 프레임이 들어갈 공간 — 그 공간의 검증된 자산 key 를 문자열로도 받는다
    public Frame assembleOwned(User user, FrameCreateRequest request, Long workspaceId) {
        KeyCheck keys = new KeyCheck(user.getPublicId(), Set.of(), workspaceId);
        BackgroundAttributes background = normalizeBackground(request.background(), keys, user.getId());
        List<FrameComponent> components = createComponents(request.components(), keys, user.getId());
        assertComposable(background, components);
        Frame frame = Frame.owned(user, request.title(), request.descriptionOrEmpty(),
                image(request.previewKey(), keys),
                request.frameType(), background, request.cellCutouts());
        components.forEach(frame::addComponent);
        return frame;
    }

    // 시스템 프레임은 소유자가 없다 — 소유권 검사를 건너뛴다(ROLE_ADMIN이 신뢰 경계다)
    public Frame assembleSystem(FrameCreateRequest request) {
        BackgroundAttributes background = normalizeBackground(request.background(), KeyCheck.NONE, null);
        List<FrameComponent> components = createComponents(request.components(), KeyCheck.NONE, null);
        assertComposable(background, components);
        Frame frame = Frame.system(request.title(), request.descriptionOrEmpty(),
                frameAssetManager.normalizeImageKey(request.previewKey(), null),
                request.frameType(), background, request.cellCutouts());
        components.forEach(frame::addComponent);
        return frame;
    }

    // 전체 교체 수정의 공통 절차: 메타데이터 갱신 + 컴포넌트 재조립 + 참조 잃은 key 삭제 예약.
    // 사용자/관리자 서비스가 각자의 관문 뒤에서 부른다 — 수집 규칙이 두 벌로 갈라지면 안 되는 코드다
    public void replaceContent(Frame frame, FrameCreateRequest request) {
        replaceContent(frame, request, frame.getUser());
    }

    /**
     * editor: 지금 고치는 사람. 개인 프레임이면 소유자 자신이고, 조직 공간 프레임이면 편집 역할의 아무 멤버다 —
     * 새로 넣는 key 는 그 사람 폴더(또는 공간 자산)여야 하고, 프레임이 이미 쓰던 key 는 그대로 받는다.
     * null 이면 시스템 프레임(검사 없음)
     */
    public void replaceContent(Frame frame, FrameCreateRequest request, User editor) {
        String oldBackgroundKey = extractBackgroundKey(frame.getBackground());
        String oldPreviewKey = frame.getPreviewKey();
        List<String> oldAssetKeys = extractAssetKeys(frame.getComponents());

        // 시스템 프레임(user=null)은 관리자 경로라 검사가 없다 — 사용자 경로에서 시스템 프레임은 관문에서 이미 404 다
        KeyCheck keys = frame.getUser() == null || editor == null
                ? KeyCheck.NONE
                : new KeyCheck(editor.getPublicId(), carriedKeys(frame), frame.getWorkspaceId());
        Long editorUserId = keys == KeyCheck.NONE ? null : editor.getId();
        BackgroundAttributes newBackground = normalizeBackground(request.background(), keys, editorUserId);
        List<FrameComponent> newComponents = createComponents(request.components(), keys, editorUserId);
        assertComposable(newBackground, newComponents);
        String newPreviewKey = image(request.previewKey(), keys);
        frame.updateMetadata(request.title(), request.descriptionOrEmpty(), newBackground,
                newPreviewKey, request.cellCutouts());

        frame.clearComponents();
        newComponents.forEach(frame::addComponent);

        // 교체로 참조를 잃은 key만 수집 — 계속 쓰이는 key를 지우면 멀쩡한 프레임이 깨진다.
        // 실제 삭제는 커밋 후(AFTER_COMMIT)라 이 아래에서 롤백돼도 파일은 무사하다
        List<String> garbage = new ArrayList<>();
        String newBackgroundKey = extractBackgroundKey(newBackground);
        if (oldBackgroundKey != null && !oldBackgroundKey.equals(newBackgroundKey)) {
            garbage.add(oldBackgroundKey);
        }
        if (!oldPreviewKey.equals(newPreviewKey)) {
            garbage.add(oldPreviewKey);
        }
        Set<String> keptAssetKeys = Set.copyOf(extractAssetKeys(newComponents));
        oldAssetKeys.stream().filter(key -> !keptAssetKeys.contains(key)).forEach(garbage::add);
        scheduleDeletion(garbage);
    }

    /**
     * 프레임이 놓아 버린 key 를 커밋 후 지운다. 단 자산 행이 있는 key 는 건너뛴다 — 발행본이 편입했거나
     * 초안이 참조할 수 있어서, 자산은 미참조 자산 정리 배치가 참조를 세어 지운다(ADR-0006 §2).
     * 사용자 삭제·관리자 삭제·탈퇴·수정이 모두 이 입구를 쓴다.
     */
    public void scheduleDeletion(java.util.Collection<String> keys) {
        List<String> candidates = keys.stream().filter(Objects::nonNull).distinct().toList();
        if (candidates.isEmpty()) {
            return;
        }
        Set<String> assetBacked = frameAssetResolver.assetBackedKeys(candidates);
        frameAssetManager.deleteAfterCommit(candidates.stream().filter(key -> !assetBacked.contains(key)).toList());
    }

    // 프레임이 지금 쓰는 uploads/ key 전부(자산으로 받은 것 포함) — 다시 저장할 때 소유권을 다시 묻지 않는다
    private Set<String> carriedKeys(Frame frame) {
        Set<String> keys = new HashSet<>();
        frame.getComponents().forEach(c -> Stream.of(c.getType() == ComponentType.TEXT ? null : c.getSource(), c.getRenderedKey())
                .filter(Objects::nonNull).map(S3Keys::normalizeManagedKey).forEach(keys::add));
        if (frame.getBackground() instanceof BackgroundAttributes.Image image && image.key() != null) {
            keys.add(image.key());
        }
        keys.add(frame.getPreviewKey());
        return keys;
    }

    /** 조직 공간 프레임 수 상한(개인 요금제 한도와 별개) */
    public void assertWorkspaceRoom(long currentFrames) {
        if (currentFrames >= limits.maxFramesPerWorkspace()) {
            throw new BusinessException(FrameErrorCode.WORKSPACE_FRAME_LIMIT,
                    "공간 프레임 " + currentFrames + "개 ≥ 상한 " + limits.maxFramesPerWorkspace());
        }
    }

    // 삭제 시 예약할 key 전부: 사진·구운 텍스트 + 배경 + 프리뷰.
    // COLOR 배경의 null은 deleteAfterCommit 필터가 거른다
    public List<String> collectAllKeys(Frame frame) {
        List<String> keys = new ArrayList<>(extractAssetKeys(frame.getComponents()));
        keys.add(extractBackgroundKey(frame.getBackground()));
        keys.add(frame.getPreviewKey());
        return keys;
    }

    public FrameResponse toFrameResponse(Frame frame) {
        // 자산 참조는 내부 id 로 저장돼 있다 — 응답에는 public id 로 바꿔 내보낸다(한 번의 조회)
        Set<Long> assetIds = new HashSet<>();
        frame.getComponents().forEach(c -> Stream.of(c.getAssetId(), c.getRenderedAssetId())
                .filter(Objects::nonNull).forEach(assetIds::add));
        Map<Long, String> publicIds = frameAssetResolver.publicIds(assetIds);

        List<FrameResponse.ComponentResponse> components = frame.getComponents().stream()
                .sorted(Comparator.comparingInt(FrameComponent::getZIndex))
                .map(c -> FrameResponse.ComponentResponse.of(
                        c,
                        frameAssetManager.resolveSource(c.getType(), c.getSource()),
                        c.getRenderedKey() == null ? null : frameAssetManager.resolveImageSource(c.getRenderedKey()),
                        publicIdOf(publicIds, c.getAssetId()),
                        publicIdOf(publicIds, c.getRenderedAssetId())))
                .toList();

        return FrameResponse.of(
                frame,
                frameAssetResolver.workspacePublicId(frame.getWorkspaceId()),
                frameAssetManager.resolveImageSource(frame.getPreviewKey()),
                resolveBackgroundUrl(frame.getBackground()),
                components);
    }

    // 자산 없는 컴포넌트(id null)가 대부분이다 — 불변 Map 은 get(null) 에서 NPE 를 던진다
    private static String publicIdOf(Map<Long, String> publicIds, Long assetId) {
        return assetId == null ? null : publicIds.get(assetId);
    }

    // IMAGE 배경에만 presigned url 주입 — url은 응답에서만 존재하는 필드
    private BackgroundAttributes resolveBackgroundUrl(BackgroundAttributes background) {
        return switch (background) {
            case BackgroundAttributes.Image image ->
                    image.withUrl(frameAssetManager.resolveImageSource(image.key()));
            case BackgroundAttributes.Color color -> color;
        };
    }
}
