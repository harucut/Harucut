package com.harucut.frame.version;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetStatus;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameVersionAssetRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 발행의 실제 JPA 경로 — 잠금 조회, 자산 편입(INSERT), 발행본·참조 저장, 프레임 삭제 시 발행본 정리.
 * S3 만 가짜다: HEAD 는 크기를, 다운로드는 실제 PNG 바이트를 준다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("프레임 발행 — 통합")
class FrameVersionPublishIntegrationTest {

    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameVersionRepository frameVersionRepository;
    @Autowired private FrameVersionAssetRepository frameVersionAssetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean
    private FileStorageService fileStorageService;

    private User user;
    private byte[] png;

    @BeforeEach
    void setUp() throws IOException {
        user = userRepository.save(User.localUser("publisher@harucut.com", "encoded", "발행자"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB), "png", out);
        png = out.toByteArray();
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);
        given(fileStorageService.generatePresignedGetUrl(anyString(), any(Duration.class)))
                .willAnswer(invocation -> "https://s3/" + invocation.getArgument(0));
        given(fileStorageService.generatePresignedGetUrl(anyString())).willAnswer(i -> "https://s3/" + i.getArgument(0));
    }

    @Test
    @DisplayName("예전 key 이미지를 자산으로 편입하고 v1·v2 를 차례로 발행한다. 프레임 version 은 그대로다")
    void publishesAndAdoptsLegacyKeys() {
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest(
                "웨딩", null, root + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Image(root + "components/bg.png", 0.8, null), null,
                List.of(component(ComponentType.STICKER, root + "components/heart.png", null, 1),
                        component(ComponentType.TEXT, "우리 결혼해요", root + "components/text.png", 2))));

        FrameVersionResponse v1 = frameVersionService.publish(user.getPublicId(), frame.frameId());
        FrameVersionResponse v2 = frameVersionService.publish(user.getPublicId(), frame.frameId());

        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v2.versionNo()).isEqualTo(2);
        FrameSpecV2 spec = v1.spec();
        assertThat(spec.schemaVersion()).isEqualTo(2);
        assertThat(spec.layout().slots()).hasSize(4);
        assertThat(spec.slotBindings()).extracting(FrameSpecV2.SlotBinding::sourceIndex).containsExactly(0, 1, 2, 3);
        assertThat(spec.layers()).extracting(FrameSpecV2.Layer::kind).containsExactly("IMAGE", "TEXT");
        assertThat(spec.layers().get(1).text().content()).isEqualTo("우리 결혼해요");
        assertThat(spec.layers()).allSatisfy(layer -> assertThat(layer.url()).startsWith("https://s3/"));

        // 배경·스티커·글자 3개가 VERIFIED 자산이 됐고, 발행본마다 3개씩 참조한다
        assertThat(assetRepository.findAll()).hasSize(3).allMatch(a -> a.getStatus() == AssetStatus.VERIFIED);
        assertThat(frameVersionAssetRepository.findAll()).hasSize(6);
        // 프레임은 손대지 않았다 — 편집기의 다음 PUT 이 409 가 되지 않는다
        assertThat(frameService.getFrame(user.getPublicId(), frame.frameId()).version()).isEqualTo(frame.version());

        // 저장된 발행본을 다시 읽어도 같은 문서다
        FrameVersionResponse read = frameVersionService.get(user.getPublicId(), frame.frameId(), 1);
        assertThat(read.spec().layers()).hasSize(2);
        assertThat(frameVersionService.list(user.getPublicId(), frame.frameId()))
                .extracting(FrameVersionResponse::versionNo).containsExactly(2, 1);
    }

    @Test
    @DisplayName("프레임을 지우면 발행본과 참조도 지워지고, 자산 행은 남는다(정리 배치 몫)")
    void deletingFrameRemovesVersions() {
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest(
                "삭제", null, root + "frames/p.png", FrameType.GRID, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null,
                List.of(component(ComponentType.PHOTO, root + "components/photo.png", null, 1))));
        frameVersionService.publish(user.getPublicId(), frame.frameId());

        frameService.deleteFrame(user.getPublicId(), frame.frameId());

        assertThat(frameVersionRepository.findAll()).isEmpty();
        assertThat(frameVersionAssetRepository.findAll()).isEmpty();
        assertThat(assetRepository.findAll()).extracting(Asset::getS3Key).containsExactly(root + "components/photo.png");
    }

    @Test
    @DisplayName("정적 경로 스티커가 있으면 발행할 수 없다(GEN-002)")
    void staticStickerCannotBePublished() {
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest(
                "정적", null, root + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null,
                List.of(component(ComponentType.STICKER, "/stickers/sticker-001.png", null, 1))));

        assertThatThrownBy(() -> frameVersionService.publish(user.getPublicId(), frame.frameId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("편입 검증에 실패하면 FRAME-005 — 깨진 파일")
    void brokenLegacyFileRejected() {
        given(fileStorageService.downloadBytes(anyString())).willReturn("broken".getBytes());
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(6, "image/png")));
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest(
                "깨짐", null, root + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null,
                List.of(component(ComponentType.PHOTO, root + "components/broken.png", null, 1))));

        assertThatThrownBy(() -> frameVersionService.publish(user.getPublicId(), frame.frameId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.ASSET_NOT_USABLE);
    }

    @Test
    @DisplayName("남의 프레임은 발행할 수 없다(404)")
    void othersFrameHidden() {
        User other = userRepository.save(User.localUser("other@harucut.com", "encoded", "남"));
        String root = S3Keys.userRoot(user.getPublicId());
        FrameResponse frame = frameService.createFrame(user.getPublicId(), new FrameCreateRequest(
                "내 것", null, root + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of()));

        assertThatThrownBy(() -> frameVersionService.publish(other.getPublicId(), frame.frameId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    // TEXT 는 source 가 글자 내용이고 renderedKey 가 구운 PNG, 나머지는 source 가 이미지 key
    private static FrameCreateRequest.ComponentRequest component(ComponentType type, String source,
                                                                 String renderedKey, int zIndex) {
        return new FrameCreateRequest.ComponentRequest(null, type, source, renderedKey,
                10.0, 20.0, 100.0, 50.0, 1.0, 0.0, zIndex, null);
    }
}
