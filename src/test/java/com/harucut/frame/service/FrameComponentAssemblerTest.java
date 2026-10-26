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
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrameComponentAssembler")
class FrameComponentAssemblerTest {

    private static final String OWNER = "AbCdEf12Gh";
    private static final String PHOTO_URL =
            "https://harucut-test.s3.amazonaws.com/uploads/users/AbCdEf12Gh/components/photo1.png";
    private static final String PHOTO_KEY = "uploads/users/AbCdEf12Gh/components/photo1.png";
    private static final Long OWNER_ID = 1L;
    private static final String STICKER_KEY = "uploads/users/AbCdEf12Gh/components/sticker1.png";
    private static final String PREVIEW_KEY = "uploads/users/AbCdEf12Gh/webm/preview.png";
    private static final String BG_KEY = "uploads/users/AbCdEf12Gh/components/bg.png";
    private static final String RENDERED_URL =
            "https://harucut-test.s3.amazonaws.com/uploads/users/AbCdEf12Gh/components/text1.png";
    private static final String RENDERED_KEY = "uploads/users/AbCdEf12Gh/components/text1.png";

    @Mock
    private FrameAssetManager frameAssetManager;

    @Mock
    private FrameAssetResolver frameAssetResolver;

    private FrameComponentAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new FrameComponentAssembler(frameAssetManager, frameAssetResolver,
                new FrameLimitsProperties(40, 7000), JsonMapper.builder().build());
    }

    @Nested
    @DisplayName("createComponents — 요청 → 엔티티")
    class CreateComponents {

        @Test
        @DisplayName("컴포넌트 목록이 null이면 빈 리스트다")
        void nullBecomesEmptyList() {
            assertThat(assembler.createComponents(null, OWNER, OWNER_ID)).isEmpty();
            then(frameAssetManager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("PHOTO source는 정규화된 key로 저장되고 나머지 필드는 그대로 옮겨진다")
        void mapsFieldsAndNormalizesSource() {
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq(PHOTO_URL), any())).willReturn(PHOTO_KEY);
            FrameCreateRequest.ComponentRequest request = new FrameCreateRequest.ComponentRequest(
                    "comp-1", ComponentType.PHOTO, PHOTO_URL, null,
                    120.5, 220.0, 360.0, 480.0, 1.5, 45.0, 3, Map.of("borderRadius", 8));

            List<FrameComponent> components = assembler.createComponents(List.of(request), OWNER, OWNER_ID);

            FrameComponent component = components.get(0);
            assertThat(component.getSource()).isEqualTo(PHOTO_KEY);
            assertThat(component.getType()).isEqualTo(ComponentType.PHOTO);
            assertThat(component.getX()).isEqualTo(120.5);
            assertThat(component.getY()).isEqualTo(220.0);
            assertThat(component.getWidth()).isEqualTo(360.0);
            assertThat(component.getHeight()).isEqualTo(480.0);
            assertThat(component.getScale()).isEqualTo(1.5);
            assertThat(component.getRotation()).isEqualTo(45.0);
            assertThat(component.getZIndex()).isEqualTo(3);
            assertThat(component.getStyle()).isEqualTo(Map.of("borderRadius", 8));
        }

        @Test
        @DisplayName("styleJson이 null이어도 엔티티의 style은 빈 맵이다")
        void nullStyleBecomesEmptyMap() {
            given(frameAssetManager.normalizeSource(eq(ComponentType.TEXT), eq("봄 여행"), any())).willReturn("봄 여행");
            FrameCreateRequest.ComponentRequest request = new FrameCreateRequest.ComponentRequest(
                    null, ComponentType.TEXT, "봄 여행", null,
                    0.0, 0.0, null, null, null, 0.0, 0, null);

            List<FrameComponent> components = assembler.createComponents(List.of(request), OWNER, OWNER_ID);

            assertThat(components.get(0).getStyle()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("TEXT의 renderedKey는 URL로 와도 정규화된 key로 저장된다")
        void textRenderedKeyNormalized() {
            given(frameAssetManager.normalizeSource(eq(ComponentType.TEXT), eq("봄 여행"), any())).willReturn("봄 여행");
            given(frameAssetManager.normalizeImageKey(eq(RENDERED_URL), any())).willReturn(RENDERED_KEY);
            FrameCreateRequest.ComponentRequest request =
                    componentRequest(ComponentType.TEXT, "봄 여행", RENDERED_URL);

            List<FrameComponent> components = assembler.createComponents(List.of(request), OWNER, OWNER_ID);

            assertThat(components.get(0).getRenderedKey()).isEqualTo(RENDERED_KEY);
        }

        @Test
        @DisplayName("PHOTO가 renderedKey를 보내면 버린다 — TEXT 전용 필드다")
        void photoRenderedKeyDiscarded() {
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq(PHOTO_URL), any())).willReturn(PHOTO_KEY);
            FrameCreateRequest.ComponentRequest request =
                    componentRequest(ComponentType.PHOTO, PHOTO_URL, RENDERED_URL);

            List<FrameComponent> components = assembler.createComponents(List.of(request), OWNER, OWNER_ID);

            assertThat(components.get(0).getRenderedKey()).isNull();
            then(frameAssetManager).should(never()).normalizeImageKey(anyString(), any());
        }

        @Test
        @DisplayName("TEXT라도 renderedKey가 빈 값이면 null로 저장된다 — 선택 필드")
        void blankRenderedKeyBecomesNull() {
            given(frameAssetManager.normalizeSource(eq(ComponentType.TEXT), eq("봄 여행"), any())).willReturn("봄 여행");
            FrameCreateRequest.ComponentRequest request =
                    componentRequest(ComponentType.TEXT, "봄 여행", "  ");

            List<FrameComponent> components = assembler.createComponents(List.of(request), OWNER, OWNER_ID);

            assertThat(components.get(0).getRenderedKey()).isNull();
        }
    }

    @Nested
    @DisplayName("extractAssetKeys")
    class ExtractAssetKeys {

        @Test
        @DisplayName("PHOTO·STICKER 의 source 와 TEXT 의 renderedKey 를 모은다 — TEXT 본문은 빠진다")
        void collectsImageSourcesAndRenderedKeys() {
            List<FrameComponent> components = List.of(
                    component(ComponentType.PHOTO, PHOTO_KEY),
                    component(ComponentType.STICKER, STICKER_KEY),
                    component(ComponentType.STICKER, "/static/stickers/heart.png"),
                    textComponent("봄 여행", RENDERED_KEY),
                    textComponent("안 구운 텍스트", null));

            // 정적 스티커 경로도 후보에 들지만 S3Deleter 가 관리 대상(uploads/)만 지운다
            assertThat(assembler.extractAssetKeys(components))
                    .containsExactly(PHOTO_KEY, STICKER_KEY, "/static/stickers/heart.png", RENDERED_KEY);
        }

        @Test
        @DisplayName("자산(assetId)으로 들어온 이미지는 즉시 지우지 않는다 — 초안·발행본도 참조할 수 있어 정리 배치 몫")
        void skipsAssetBackedKeys() {
            FrameComponent assetBacked = FrameComponent.builder()
                    .source(STICKER_KEY).type(ComponentType.STICKER).assetId(7L).build();
            FrameComponent assetText = FrameComponent.builder()
                    .source("봄").type(ComponentType.TEXT).renderedKey(RENDERED_KEY).renderedAssetId(8L).build();

            assertThat(assembler.extractAssetKeys(List.of(assetBacked, assetText))).isEmpty();
        }
    }

    @Nested
    @DisplayName("배경 정규화·key 추출")
    class Background {

        @Test
        @DisplayName("IMAGE 배경의 key가 정규화되고 url 흔적은 비워진다")
        void normalizesImageKeyAndStripsUrl() {
            given(frameAssetManager.normalizeImageKey(eq(PHOTO_URL), any())).willReturn(BG_KEY);
            BackgroundAttributes.Image withUrl =
                    new BackgroundAttributes.Image(PHOTO_URL, 0.8, null).withUrl("https://presigned");

            BackgroundAttributes normalized = assembler.normalizeBackground(withUrl, OWNER, OWNER_ID);

            assertThat(normalized).isEqualTo(new BackgroundAttributes.Image(BG_KEY, 0.8, null));
        }

        @Test
        @DisplayName("COLOR 배경은 손대지 않고 그대로 통과한다")
        void colorPassesThrough() {
            BackgroundAttributes color = new BackgroundAttributes.Color("#FFE4E1");

            assertThat(assembler.normalizeBackground(color, OWNER, OWNER_ID)).isSameAs(color);
            then(frameAssetManager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("삭제 후보 key는 IMAGE에서만 나오고 COLOR는 null이다")
        void extractsKeyOnlyFromImage() {
            assertThat(assembler.extractBackgroundKey(new BackgroundAttributes.Image(BG_KEY, 0.8, null)))
                    .isEqualTo(BG_KEY);
            assertThat(assembler.extractBackgroundKey(new BackgroundAttributes.Color("#FFF"))).isNull();
        }
    }

    /*
     * P1 자산 참조 — assetId·renderedAssetId·배경 assetId 를 실제 key 로 바꾸고, 응답에는 public id 로 돌려준다.
     * 쓸 수 있는지(FRAME-005)는 FrameAssetResolver 가 판정한다(FrameAssetResolverTest).
     */
    @Nested
    @DisplayName("자산 참조·잠금·상한 (P1)")
    class AssetReferences {

        @Test
        @DisplayName("assetId 가 있으면 source 대신 그 자산의 key 를 저장하고 자산 id 를 남긴다")
        void resolvesSourceAsset() {
            Asset asset = verifiedAsset(7L, "As7", STICKER_KEY, AssetPurpose.FRAME_ASSET);
            given(frameAssetResolver.require("As7", AssetPurpose.FRAME_ASSET, OWNER_ID)).willReturn(asset);
            FrameCreateRequest.ComponentRequest request = new FrameCreateRequest.ComponentRequest(
                    null, ComponentType.STICKER, "https://ignored.example/x.png", null,
                    0.0, 0.0, 50.0, 50.0, 1.0, 0.0, 1, null, "As7", null, true);

            FrameComponent component = assembler.createComponents(List.of(request), OWNER, OWNER_ID).get(0);

            assertThat(component.getSource()).isEqualTo(STICKER_KEY);
            assertThat(component.getAssetId()).isEqualTo(7L);
            assertThat(component.isLocked()).isTrue();
            then(frameAssetManager).should(never()).normalizeSource(any(), any(), any());
        }

        @Test
        @DisplayName("TEXT 의 renderedAssetId 는 TEXT_RENDER 자산으로 해결한다")
        void resolvesRenderedAsset() {
            Asset asset = verifiedAsset(8L, "As8", RENDERED_KEY, AssetPurpose.TEXT_RENDER);
            given(frameAssetResolver.require("As8", AssetPurpose.TEXT_RENDER, OWNER_ID)).willReturn(asset);
            given(frameAssetManager.normalizeSource(eq(ComponentType.TEXT), eq("봄"), any())).willReturn("봄");
            FrameCreateRequest.ComponentRequest request = new FrameCreateRequest.ComponentRequest(
                    null, ComponentType.TEXT, "봄", null, 0.0, 0.0, 10.0, 10.0, 1.0, 0.0, 1, null,
                    null, "As8", null);

            FrameComponent component = assembler.createComponents(List.of(request), OWNER, OWNER_ID).get(0);

            assertThat(component.getRenderedKey()).isEqualTo(RENDERED_KEY);
            assertThat(component.getRenderedAssetId()).isEqualTo(8L);
        }

        @Test
        @DisplayName("renderedKey 로 보내도 그 key 가 자산이면 자산으로 묶는다(같은 검사)")
        void renderedKeyMatchingAsset() {
            Asset asset = verifiedAsset(9L, "As9", RENDERED_KEY, AssetPurpose.TEXT_RENDER);
            given(frameAssetManager.normalizeImageKey(eq(RENDERED_KEY), any())).willReturn(RENDERED_KEY);
            given(frameAssetResolver.findByKey(RENDERED_KEY, AssetPurpose.TEXT_RENDER, OWNER_ID))
                    .willReturn(java.util.Optional.of(asset));
            FrameCreateRequest.ComponentRequest request = new FrameCreateRequest.ComponentRequest(
                    null, ComponentType.TEXT, "봄", RENDERED_KEY, 0.0, 0.0, 10.0, 10.0, 1.0, 0.0, 1, null);

            FrameComponent component = assembler.createComponents(List.of(request), OWNER, OWNER_ID).get(0);

            assertThat(component.getRenderedAssetId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("레이어가 상한(40)을 넘으면 400 FRAME-003")
        void tooManyLayers() {
            List<FrameCreateRequest.ComponentRequest> many = java.util.stream.IntStream.range(0, 41)
                    .mapToObj(i -> new FrameCreateRequest.ComponentRequest(null, ComponentType.TEXT, "t" + i,
                            null, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0, i, null))
                    .toList();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> assembler.createComponents(many, OWNER, OWNER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(FrameErrorCode.TOO_MANY_LAYERS);
        }

        @Test
        @DisplayName("합성 스펙이 상한을 넘으면 400 FRAME-004 — 합성 때 DB 오류로 죽기 전에")
        void specTooLarge() {
            FrameComponentAssembler tight = new FrameComponentAssembler(frameAssetManager, frameAssetResolver,
                    new FrameLimitsProperties(40, 300), JsonMapper.builder().build());
            given(frameAssetManager.normalizeSource(any(), anyString(), any()))
                    .willAnswer(invocation -> invocation.getArgument(1));
            List<FrameCreateRequest.ComponentRequest> layers = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> new FrameCreateRequest.ComponentRequest(null, ComponentType.PHOTO,
                            PHOTO_KEY, null, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0, i, null))
                    .toList();
            User owner = User.localUser("o@harucut.com", "encoded", "o");
            FrameCreateRequest request = new FrameCreateRequest("제목", null, PREVIEW_KEY, FrameType.CLASSIC,
                    null, null, new BackgroundAttributes.Color("#FFFFFF"), null, layers);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tight.assembleOwned(owner, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(FrameErrorCode.SPEC_TOO_LARGE);
        }

        @Test
        @DisplayName("배경 assetId 는 그 자산의 key 로 바뀌고 assetId 가 남는다")
        void resolvesBackgroundAsset() {
            Asset asset = verifiedAsset(3L, "As3", BG_KEY, AssetPurpose.FRAME_ASSET);
            given(frameAssetResolver.require("As3", AssetPurpose.FRAME_ASSET, OWNER_ID)).willReturn(asset);
            BackgroundAttributes.Image request = new BackgroundAttributes.Image("https://ignored", 0.8, null,
                    "As3", BackgroundAttributes.Fit.CONTAIN, 0.2, null, null);

            BackgroundAttributes.Image normalized =
                    (BackgroundAttributes.Image) assembler.normalizeBackground(request, OWNER, OWNER_ID);

            assertThat(normalized.key()).isEqualTo(BG_KEY);
            assertThat(normalized.assetId()).isEqualTo("As3");
            assertThat(normalized.fit()).isEqualTo(BackgroundAttributes.Fit.CONTAIN);
            assertThat(normalized.focalX()).isEqualTo(0.2);
            // 자산 배경은 즉시 삭제 대상이 아니다
            assertThat(assembler.extractBackgroundKey(normalized)).isNull();
        }

        @Test
        @DisplayName("응답: 자산 public id·잠금·구운 PNG key·URL·version 이 실린다")
        void responseCarriesNewFields() {
            given(frameAssetManager.resolveImageSource(anyString())).willAnswer(i -> "url:" + i.getArgument(0));
            given(frameAssetManager.resolveSource(any(), anyString())).willAnswer(i -> i.getArgument(1));
            given(frameAssetResolver.publicIds(any())).willReturn(Map.of(7L, "As7", 8L, "As8"));
            Frame frame = colorFrame();
            frame.addComponent(FrameComponent.builder().source(STICKER_KEY).type(ComponentType.STICKER)
                    .assetId(7L).locked(true).build());
            frame.addComponent(FrameComponent.builder().source("봄").type(ComponentType.TEXT)
                    .renderedKey(RENDERED_KEY).renderedAssetId(8L).zIndex(1).build());

            FrameResponse response = assembler.toFrameResponse(frame);

            FrameResponse.ComponentResponse sticker = response.components().get(0);
            assertThat(sticker.assetId()).isEqualTo("As7");
            assertThat(sticker.locked()).isTrue();
            assertThat(sticker.key()).isEqualTo(STICKER_KEY);
            FrameResponse.ComponentResponse text = response.components().get(1);
            assertThat(text.renderedAssetId()).isEqualTo("As8");
            assertThat(text.renderedKey()).isEqualTo(RENDERED_KEY);
            assertThat(text.renderedUrl()).isEqualTo("url:" + RENDERED_KEY);
        }

        private Asset verifiedAsset(Long id, String publicId, String key, AssetPurpose purpose) {
            Asset asset = Asset.pendingUpload(publicId, 10L, purpose, key, "image/png", 1L, OWNER_ID);
            asset.verify(1L, 10, 10, java.time.LocalDateTime.of(2026, 10, 26, 10, 0));
            org.springframework.test.util.ReflectionTestUtils.setField(asset, "id", id);
            return asset;
        }
    }

    @Nested
    @DisplayName("toFrameResponse — 엔티티 → 응답")
    class ToFrameResponse {

        @Test
        @DisplayName("컴포넌트가 zIndex 오름차순으로 나오고 동률은 추가 순서를 지킨다")
        void sortsByZIndexStably() {
            given(frameAssetManager.resolveImageSource(PREVIEW_KEY)).willReturn("https://preview");
            given(frameAssetManager.resolveSource(any(), anyString()))
                    .willAnswer(invocation -> invocation.getArgument(1));
            Frame frame = colorFrame();
            frame.addComponent(componentWithZIndex("셋", 3));
            frame.addComponent(componentWithZIndex("일-먼저", 1));
            frame.addComponent(componentWithZIndex("일-나중", 1));
            frame.addComponent(componentWithZIndex("둘", 2));

            FrameResponse response = assembler.toFrameResponse(frame);

            assertThat(response.components())
                    .extracting(FrameResponse.ComponentResponse::source)
                    .containsExactly("일-먼저", "일-나중", "둘", "셋");
        }

        @Test
        @DisplayName("width/height null은 0.0으로, scale null은 1.0으로 보정된다")
        void nullDimensionDefaults() {
            given(frameAssetManager.resolveImageSource(PREVIEW_KEY)).willReturn("https://preview");
            given(frameAssetManager.resolveSource(any(), anyString()))
                    .willAnswer(invocation -> invocation.getArgument(1));
            Frame frame = colorFrame();
            frame.addComponent(FrameComponent.builder()
                    .source("봄 여행").type(ComponentType.TEXT).build());

            FrameResponse.ComponentResponse component =
                    assembler.toFrameResponse(frame).components().get(0);

            assertThat(component.width()).isZero();
            assertThat(component.height()).isZero();
            assertThat(component.scale()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("캔버스 크기는 frameType 파생 고정값이고 key·source가 분리돼 나간다")
        void canvasDerivedAndSourceResolved() {
            given(frameAssetManager.resolveImageSource(PREVIEW_KEY)).willReturn("https://preview");
            given(frameAssetManager.resolveSource(ComponentType.PHOTO, PHOTO_KEY))
                    .willReturn("https://photo-presigned");
            Frame frame = colorFrame();
            frame.addComponent(component(ComponentType.PHOTO, PHOTO_KEY));

            FrameResponse response = assembler.toFrameResponse(frame);

            assertThat(response.canvasWidth()).isEqualTo(2000);
            assertThat(response.canvasHeight()).isEqualTo(6000);
            assertThat(response.source()).isEqualTo("https://preview");
            assertThat(response.isSystem()).isTrue();
            FrameResponse.ComponentResponse component = response.components().get(0);
            assertThat(component.source()).isEqualTo("https://photo-presigned");
            assertThat(component.key()).isEqualTo(PHOTO_KEY);
        }

        @Test
        @DisplayName("셀 누끼 토글이 응답에 그대로 실린다 — 편집기 재오픈 시 복원용")
        void cellCutoutsIncluded() {
            given(frameAssetManager.resolveImageSource(PREVIEW_KEY)).willReturn("https://preview");
            Frame frame = Frame.system("기본", "설명", PREVIEW_KEY, FrameType.CLASSIC,
                    new BackgroundAttributes.Color("#FFE4E1"),
                    List.of(true, false, false, false));

            FrameResponse response = assembler.toFrameResponse(frame);

            assertThat(response.cellCutouts()).containsExactly(true, false, false, false);
        }

        @Test
        @DisplayName("IMAGE 배경에는 presigned url이 주입돼 나간다")
        void imageBackgroundGetsUrl() {
            given(frameAssetManager.resolveImageSource(PREVIEW_KEY)).willReturn("https://preview");
            given(frameAssetManager.resolveImageSource(BG_KEY)).willReturn("https://bg-presigned");
            Frame frame = Frame.system("기본", "설명", PREVIEW_KEY, FrameType.CLASSIC,
                    new BackgroundAttributes.Image(BG_KEY, 0.8, null));

            FrameResponse response = assembler.toFrameResponse(frame);

            assertThat(response.background())
                    .isEqualTo(new BackgroundAttributes.Image(BG_KEY, 0.8, "https://bg-presigned"));
        }
    }

    @Nested
    @DisplayName("assembleOwned / assembleSystem — 생성 조립")
    class Assemble {

        @Test
        @DisplayName("소유 프레임은 소유자를 갖고 프리뷰·배경이 정규화되어 조립된다")
        void assemblesOwnedFrame() {
            User user = User.localUser("user@harucut.com", "encoded", "하루컷");
            given(frameAssetManager.normalizeImageKey(eq("preview-url"), any())).willReturn(PREVIEW_KEY);
            FrameCreateRequest request = new FrameCreateRequest("제목", null, "preview-url",
                    FrameType.CLASSIC, null, null, new BackgroundAttributes.Color("#FFF"),
                    List.of(true, false, false, true), null);

            Frame frame = assembler.assembleOwned(user, request);

            assertThat(frame.getUser()).isSameAs(user);
            assertThat(frame.isSystem()).isFalse();
            assertThat(frame.getPreviewKey()).isEqualTo(PREVIEW_KEY);
            assertThat(frame.getDescription()).isEmpty();
            assertThat(frame.getBackground()).isEqualTo(new BackgroundAttributes.Color("#FFF"));
            assertThat(frame.getCellCutouts()).containsExactly(true, false, false, true);
        }

        @Test
        @DisplayName("시스템 프레임은 소유자 없이 조립되고 컴포넌트 source도 정규화된다")
        void assemblesSystemFrame() {
            given(frameAssetManager.normalizeImageKey(eq("preview-url"), any())).willReturn(PREVIEW_KEY);
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq(PHOTO_URL), any())).willReturn(PHOTO_KEY);
            FrameCreateRequest request = new FrameCreateRequest("기본", "설명", "preview-url",
                    FrameType.CLASSIC, null, null, new BackgroundAttributes.Color("#FFF"), null,
                    List.of(componentRequest(ComponentType.PHOTO, PHOTO_URL)));

            Frame frame = assembler.assembleSystem(request);

            assertThat(frame.getUser()).isNull();
            assertThat(frame.isSystem()).isTrue();
            assertThat(frame.getComponents()).extracting(FrameComponent::getSource)
                    .containsExactly(PHOTO_KEY);
        }
    }

    @Nested
    @DisplayName("replaceContent — 전체 교체와 잃은 참조 수집")
    class ReplaceContent {

        @Test
        @DisplayName("교체된 배경·프리뷰·빠진 사진만 삭제 예약된다 — kept는 절대 지우지 않는다")
        void collectsOnlyOrphanedKeys() {
            Frame frame = Frame.system("옛 제목", "설명", "uploads/old-preview.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image("uploads/old-bg.png", 0.8, null));
            frame.addComponent(component(ComponentType.PHOTO, "uploads/old1.png"));
            frame.addComponent(component(ComponentType.PHOTO, "uploads/kept.png"));
            FrameCreateRequest request = new FrameCreateRequest("새 제목", null, "new-preview-url",
                    FrameType.CLASSIC, null, null,
                    new BackgroundAttributes.Image("new-bg-url", 0.5, null), null,
                    List.of(componentRequest(ComponentType.PHOTO, "uploads/kept.png"),
                            componentRequest(ComponentType.PHOTO, "uploads/new1.png")));
            given(frameAssetManager.normalizeImageKey(eq("new-bg-url"), any())).willReturn("uploads/new-bg.png");
            given(frameAssetManager.normalizeImageKey(eq("new-preview-url"), any())).willReturn("uploads/new-preview.png");
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq("uploads/kept.png"), any()))
                    .willReturn("uploads/kept.png");
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq("uploads/new1.png"), any()))
                    .willReturn("uploads/new1.png");

            assembler.replaceContent(frame, request);

            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
            then(frameAssetManager).should().deleteAfterCommit(captor.capture());
            assertThat(captor.getValue()).containsExactly(
                    "uploads/old-bg.png", "uploads/old-preview.png", "uploads/old1.png");
            assertThat(frame.getTitle()).isEqualTo("새 제목");
            assertThat(frame.getPreviewKey()).isEqualTo("uploads/new-preview.png");
            assertThat(frame.getBackground())
                    .isEqualTo(new BackgroundAttributes.Image("uploads/new-bg.png", 0.5, null));
            assertThat(frame.getComponents()).extracting(FrameComponent::getSource)
                    .containsExactly("uploads/kept.png", "uploads/new1.png");
        }

        @Test
        @DisplayName("교체된 구운 텍스트 key는 삭제 예약되고 유지된 key는 남는다")
        void collectsOrphanedRenderedKeys() {
            Frame frame = colorFrame();
            frame.addComponent(textComponent("봄", "uploads/text-old.png"));
            frame.addComponent(textComponent("여름", "uploads/text-kept.png"));
            FrameCreateRequest request = new FrameCreateRequest("제목", null, PREVIEW_KEY,
                    FrameType.CLASSIC, null, null, new BackgroundAttributes.Color("#FFE4E1"), null,
                    List.of(componentRequest(ComponentType.TEXT, "봄", "uploads/text-new.png"),
                            componentRequest(ComponentType.TEXT, "여름", "uploads/text-kept.png")));
            given(frameAssetManager.normalizeImageKey(eq(PREVIEW_KEY), any())).willReturn(PREVIEW_KEY);
            given(frameAssetManager.normalizeSource(eq(ComponentType.TEXT), anyString(), any()))
                    .willAnswer(invocation -> invocation.getArgument(1));
            given(frameAssetManager.normalizeImageKey(eq("uploads/text-new.png"), any()))
                    .willReturn("uploads/text-new.png");
            given(frameAssetManager.normalizeImageKey(eq("uploads/text-kept.png"), any()))
                    .willReturn("uploads/text-kept.png");

            assembler.replaceContent(frame, request);

            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
            then(frameAssetManager).should().deleteAfterCommit(captor.capture());
            assertThat(captor.getValue()).containsExactly("uploads/text-old.png");
        }

        @Test
        @DisplayName("배경·프리뷰·사진이 전부 그대로면 아무것도 삭제 예약되지 않는다")
        void keepsReusedKeys() {
            Frame frame = Frame.system("제목", "설명", "uploads/preview.png", FrameType.CLASSIC,
                    new BackgroundAttributes.Image("uploads/bg.png", 0.8, null));
            frame.addComponent(component(ComponentType.PHOTO, "uploads/kept.png"));
            FrameCreateRequest request = new FrameCreateRequest("제목", null, "uploads/preview.png",
                    FrameType.CLASSIC, null, null,
                    new BackgroundAttributes.Image("uploads/bg.png", 0.8, null), null,
                    List.of(componentRequest(ComponentType.PHOTO, "uploads/kept.png")));
            given(frameAssetManager.normalizeImageKey(eq("uploads/bg.png"), any())).willReturn("uploads/bg.png");
            given(frameAssetManager.normalizeImageKey(eq("uploads/preview.png"), any())).willReturn("uploads/preview.png");
            given(frameAssetManager.normalizeSource(eq(ComponentType.PHOTO), eq("uploads/kept.png"), any()))
                    .willReturn("uploads/kept.png");

            assembler.replaceContent(frame, request);

            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
            then(frameAssetManager).should().deleteAfterCommit(captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("전체 교체는 셀 누끼 토글도 갱신한다")
        void replacesCellCutouts() {
            Frame frame = Frame.system("제목", "설명", PREVIEW_KEY, FrameType.CLASSIC,
                    new BackgroundAttributes.Color("#FFE4E1"),
                    List.of(true, true, true, true));
            FrameCreateRequest request = new FrameCreateRequest("제목", null, PREVIEW_KEY,
                    FrameType.CLASSIC, null, null, new BackgroundAttributes.Color("#FFE4E1"),
                    List.of(false, true, true, false), null);
            given(frameAssetManager.normalizeImageKey(eq(PREVIEW_KEY), any())).willReturn(PREVIEW_KEY);

            assembler.replaceContent(frame, request);

            assertThat(frame.getCellCutouts()).containsExactly(false, true, true, false);
        }
    }

    @Nested
    @DisplayName("collectAllKeys — 삭제용 key 수집")
    class CollectAllKeys {

        @Test
        @DisplayName("사진·구운 텍스트·배경·프리뷰 순으로 전부 수집한다")
        void collectsPhotosBackgroundPreview() {
            Frame frame = Frame.system("제목", "설명", PREVIEW_KEY, FrameType.CLASSIC,
                    new BackgroundAttributes.Image(BG_KEY, 0.8, null));
            frame.addComponent(component(ComponentType.PHOTO, PHOTO_KEY));
            frame.addComponent(textComponent("봄 여행", RENDERED_KEY));

            assertThat(assembler.collectAllKeys(frame))
                    .containsExactly(PHOTO_KEY, RENDERED_KEY, BG_KEY, PREVIEW_KEY);
        }

        @Test
        @DisplayName("COLOR 배경은 null로 실려 간다 — deleteAfterCommit 필터가 거른다는 계약")
        void colorBackgroundYieldsNull() {
            Frame frame = colorFrame();
            frame.addComponent(component(ComponentType.PHOTO, PHOTO_KEY));

            assertThat(assembler.collectAllKeys(frame))
                    .containsExactly(PHOTO_KEY, null, PREVIEW_KEY);
        }
    }

    private static FrameCreateRequest.ComponentRequest componentRequest(ComponentType type, String source) {
        return componentRequest(type, source, null);
    }

    private static FrameCreateRequest.ComponentRequest componentRequest(
            ComponentType type, String source, String renderedKey) {
        return new FrameCreateRequest.ComponentRequest(
                null, type, source, renderedKey, null, null, null, null, null, null, null, null);
    }

    private static Frame colorFrame() {
        return Frame.system("기본", "설명", PREVIEW_KEY, FrameType.CLASSIC,
                new BackgroundAttributes.Color("#FFE4E1"));
    }

    private static FrameComponent component(ComponentType type, String source) {
        return FrameComponent.builder().source(source).type(type).build();
    }

    private static FrameComponent textComponent(String source, String renderedKey) {
        return FrameComponent.builder()
                .source(source).type(ComponentType.TEXT).renderedKey(renderedKey).build();
    }

    private static FrameComponent componentWithZIndex(String source, int zIndex) {
        return FrameComponent.builder()
                .source(source).type(ComponentType.TEXT).zIndex(zIndex).build();
    }
}
