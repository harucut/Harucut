package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.config.FrameLimitsProperties;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 프레임 저장 경로의 소유권 관문. 어셈블러와 자산 관리자를 실제 객체로 묶어
// "남의 key 를 내 프레임에 심을 수 있는가"를 직접 확인한다.
//
// 이 구멍이 실제로 있었다: 저장 시점 검사가 isManagedKey("uploads/" 로 시작하는가) 하나뿐이라
// 남의 결과물 key 를 PHOTO source 에 넣으면 그대로 저장됐고, 단건 조회가 그 key 에
// presigned GET URL 을 붙여 돌려줬다 — 합성을 돌릴 필요조차 없었다.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("프레임 자산 소유권 관문")
class FrameAssetOwnershipTest {

    private static final String OTHER = "ZzZzZzZzZz99";

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private S3Deleter s3Deleter;

    @Mock
    private FrameAssetResolver frameAssetResolver;

    private FrameComponentAssembler assembler;
    private User owner;
    private String myRoot;
    private String othersKey;

    @BeforeEach
    void setUp() {
        assembler = new FrameComponentAssembler(new FrameAssetManager(fileStorageService, s3Deleter),
                frameAssetResolver, new FrameLimitsProperties(40, 7000), JsonMapper.builder().build());
        owner = User.localUser("owner@harucut.com", "encoded", "하루컷");
        myRoot = S3Keys.userRoot(owner.getPublicId());
        othersKey = S3Keys.userRoot(OTHER) + "fourcuts/job-137.png";
    }

    private FrameCreateRequest request(String previewKey, BackgroundAttributes background,
                                       List<FrameCreateRequest.ComponentRequest> components) {
        return new FrameCreateRequest("제목", null, previewKey, FrameType.CLASSIC, null, null,
                background, null, components);
    }

    private FrameCreateRequest.ComponentRequest photo(String source) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.PHOTO, source, null,
                0.0, 0.0, 100.0, 100.0, 1.0, 0.0, 0, null);
    }

    private FrameCreateRequest.ComponentRequest text(String renderedKey) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.TEXT, "봄 여행", renderedKey,
                0.0, 0.0, 100.0, 100.0, 1.0, 0.0, 0, null);
    }

    private FrameCreateRequest.ComponentRequest sticker(String source) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.STICKER, source, null,
                0.0, 0.0, 50.0, 50.0, 1.0, 0.0, 1, null);
    }

    private BackgroundAttributes color() {
        return new BackgroundAttributes.Color("#FFE4E1");
    }

    // ── 막혀야 하는 것 ─────────────────────────

    @Test
    @DisplayName("남의 key 를 PHOTO source 로 넣으면 403 이다")
    void foreignPhotoSourceIsForbidden() {
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(photo(othersKey)));

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", GlobalErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("남의 key 를 TEXT renderedKey 로 넣으면 403 이다")
    void foreignRenderedKeyIsForbidden() {
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(text(othersKey)));

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("남의 key 를 배경 이미지로 넣으면 403 이다")
    void foreignBackgroundIsForbidden() {
        FrameCreateRequest request = request(myRoot + "frames/p.png",
                new BackgroundAttributes.Image(othersKey, 0.8, null), List.of());

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("남의 key 를 previewKey 로 넣으면 403 이다")
    void foreignPreviewKeyIsForbidden() {
        FrameCreateRequest request = request(othersKey, color(), List.of());

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("남의 key 를 STICKER source 로 넣으면 403 이다")
    void foreignStickerSourceIsForbidden() {
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(sticker(othersKey)));

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", GlobalErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("URL 로 감싼 남의 STICKER key 도 막힌다")
    void urlWrappedForeignStickerIsForbidden() {
        String wrapped = "https://harucut-test.s3.ap-northeast-2.amazonaws.com/" + othersKey;
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(sticker(wrapped)));

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("수정(replaceContent)에서도 남의 STICKER key 는 막힌다")
    void updatePathGuardsStickerToo() {
        Frame frame = assembler.assembleOwned(owner,
                request(myRoot + "frames/p.png", color(), List.of()));

        assertThatThrownBy(() -> assembler.replaceContent(frame,
                request(myRoot + "frames/p.png", color(), List.of(sticker(othersKey)))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("URL 로 감싸도 정규화 후 검사하므로 우회되지 않는다")
    void urlWrappedForeignKeyIsForbidden() {
        String wrapped = "https://harucut-test.s3.ap-northeast-2.amazonaws.com/" + othersKey;
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(photo(wrapped)));

        assertThatThrownBy(() -> assembler.assembleOwned(owner, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("수정(replaceContent)도 같은 관문을 통과한다 — 생성만 막으면 우회된다")
    void updatePathIsGuardedToo() {
        Frame frame = assembler.assembleOwned(owner,
                request(myRoot + "frames/p.png", color(), List.of()));

        assertThatThrownBy(() -> assembler.replaceContent(frame,
                request(myRoot + "frames/p.png", color(), List.of(photo(othersKey)))))
                .isInstanceOf(BusinessException.class);
    }

    // ── 통과해야 하는 것 ─────────────────────────

    @Test
    @DisplayName("내 key 는 전부 통과한다")
    void ownKeysPass() {
        FrameCreateRequest request = request(myRoot + "frames/p.png",
                new BackgroundAttributes.Image(myRoot + "components/bg.png", 0.8, null),
                List.of(photo(myRoot + "components/photo.png"),
                        text(myRoot + "components/text.png")));

        assertThatCode(() -> assembler.assembleOwned(owner, request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("내 uploads/ 스티커는 통과하고 저장값도 바뀌지 않는다")
    void ownStickerPassesUnchanged() {
        String mine = "https://harucut-test.s3.ap-northeast-2.amazonaws.com/" + myRoot + "components/s.png";
        Frame frame = assembler.assembleOwned(owner,
                request(myRoot + "frames/p.png", color(), List.of(sticker(mine))));

        assertThat(frame.getComponents().get(0).getSource()).isEqualTo(mine);
    }

    @Test
    @DisplayName("시스템 프레임의 STICKER 는 검사하지 않는다")
    void systemFrameStickerSkipsCheck() {
        FrameCreateRequest request = request(othersKey, color(), List.of(sticker(othersKey)));

        assertThatCode(() -> assembler.assembleSystem(request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("우리 파일이 아닌 값은 막지 않는다 — 정적 스티커 경로와 TEXT 본문")
    void unmanagedValuesStillPass() {
        FrameCreateRequest.ComponentRequest sticker = new FrameCreateRequest.ComponentRequest(
                null, ComponentType.STICKER, "/static/stickers/heart.png", null,
                0.0, 0.0, 50.0, 50.0, 1.0, 0.0, 1, null);
        FrameCreateRequest request =
                request(myRoot + "frames/p.png", color(), List.of(sticker, text(null)));

        assertThatCode(() -> assembler.assembleOwned(owner, request)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시스템 프레임은 소유자가 없어 검사를 건너뛴다 — ROLE_ADMIN 이 신뢰 경계다")
    void systemFrameSkipsOwnershipCheck() {
        FrameCreateRequest request = request(othersKey, color(), List.of(photo(othersKey)));

        assertThatCode(() -> assembler.assembleSystem(request)).doesNotThrowAnyException();
    }
}
