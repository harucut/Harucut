package com.harucut.asset.service;

import com.harucut.asset.dto.AssetResponse;
import com.harucut.asset.dto.UploadIntentRequest;
import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.enums.AssetStatus;
import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.enums.ContentType;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetService")
class AssetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");

    @Mock private AssetRepository assetRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceService workspaceService;
    @Mock private FileStorageService fileStorageService;
    @Mock private PlatformTransactionManager transactionManager;

    private AssetService service;
    private User user;
    private Workspace personal;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        StorageProperties properties = new StorageProperties(
                new StorageProperties.Upload(Duration.ofMinutes(10), true),
                new StorageProperties.Download(Duration.ofMinutes(5)),
                new StorageProperties.Asset(2, Duration.ofSeconds(1), 40_000_000L, Duration.ofDays(7)));
        service = new AssetService(assetRepository, userRepository, workspaceService, fileStorageService,
                properties, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

        user = User.localUser("me@harucut.com", "encoded", "나");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findByPublicId(user.getPublicId())).willReturn(Optional.of(user));
        personal = Workspace.personal(1L);
        ReflectionTestUtils.setField(personal, "id", 10L);
        given(workspaceService.personalWorkspaceOf(1L)).willReturn(personal);
        given(assetRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("createIntent")
    class CreateIntent {

        @Test
        @DisplayName("개인 공간에 PENDING 자산을 만들고 사용자 폴더 key 로 조건부 PUT 을 서명한다")
        void createsPendingAssetAndPresigns() {
            given(fileStorageService.presignPut(anyString(), eq("image/png"), eq(2048L), eq(Duration.ofMinutes(10)), eq(true)))
                    .willReturn(new PresignedPut("https://s3/put", Map.of("Content-Type", "image/png", "If-None-Match", "*"),
                            NOW.plusSeconds(600)));

            UploadIntentResponse response = service.createIntent(user.getPublicId(),
                    new UploadIntentRequest(AssetPurpose.FRAME_ASSET, ContentType.PNG, 2048L, "bg.png", null));

            ArgumentCaptor<Asset> saved = ArgumentCaptor.captor();
            then(assetRepository).should().save(saved.capture());
            Asset asset = saved.getValue();
            assertThat(asset.getStatus()).isEqualTo(AssetStatus.PENDING_UPLOAD);
            assertThat(asset.getWorkspaceId()).isEqualTo(10L);
            assertThat(asset.getS3Key()).isEqualTo(
                    "uploads/users/" + user.getPublicId() + "/assets/" + asset.getPublicId() + ".png");
            assertThat(response.assetId()).isEqualTo(asset.getPublicId());
            assertThat(response.method()).isEqualTo("PUT");
            assertThat(response.headers()).containsEntry("If-None-Match", "*");
        }

        @Test
        @DisplayName("CAPTURE_INPUT·PAGE_THEME_ASSET 은 400 ASSET-005")
        void purposeNotAllowed() {
            for (AssetPurpose purpose : new AssetPurpose[]{AssetPurpose.CAPTURE_INPUT, AssetPurpose.PAGE_THEME_ASSET}) {
                assertThatThrownBy(() -> service.createIntent(user.getPublicId(),
                        new UploadIntentRequest(purpose, ContentType.PNG, 10L, "a.png", null)))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode").isEqualTo(AssetErrorCode.PURPOSE_NOT_ALLOWED);
            }
            then(assetRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("용도가 받지 않는 형식이면 415 GEN-051 — FRAME_ASSET 에 WEBP")
        void contentTypeNotAccepted() {
            assertThatThrownBy(() -> service.createIntent(user.getPublicId(),
                    new UploadIntentRequest(AssetPurpose.FRAME_ASSET, ContentType.WEBP, 10L, "a.webp", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }

        @Test
        @DisplayName("파일명 확장자가 형식과 다르면 415 GEN-051")
        void extensionMismatch() {
            assertThatThrownBy(() -> service.createIntent(user.getPublicId(),
                    new UploadIntentRequest(AssetPurpose.FRAME_ASSET, ContentType.PNG, 10L, "a.jpg", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    @Nested
    @DisplayName("complete")
    class Complete {

        private Asset pending;

        @BeforeEach
        void pendingAsset() {
            pending = Asset.pendingUpload("As8kLm2Pq9Xz", 10L, AssetPurpose.FRAME_ASSET,
                    "uploads/users/x/assets/As8kLm2Pq9Xz.png", "image/png", 0L, 1L);
            ReflectionTestUtils.setField(pending, "id", 5L);
            given(assetRepository.findByPublicId("As8kLm2Pq9Xz")).willReturn(Optional.of(pending));
            given(assetRepository.findById(5L)).willReturn(Optional.of(pending));
        }

        @Test
        @DisplayName("정상 PNG 면 VERIFIED 로 바뀌고 크기·URL 이 실린다")
        void verifiesValidImage() throws Exception {
            byte[] png = png(64, 32);
            ReflectionTestUtils.setField(pending, "declaredSize", (long) png.length);
            given(fileStorageService.head(pending.getS3Key()))
                    .willReturn(Optional.of(new StoredObject(png.length, "image/png")));
            given(fileStorageService.downloadBytes(pending.getS3Key())).willReturn(png);
            given(fileStorageService.generatePresignedGetUrl(pending.getS3Key(), Duration.ofMinutes(5)))
                    .willReturn("https://s3/get");

            AssetService.CompleteResult result = service.complete(user.getPublicId(), "As8kLm2Pq9Xz");

            assertThat(result.rejection()).isNull();
            AssetResponse asset = result.asset();
            assertThat(asset.status()).isEqualTo(AssetStatus.VERIFIED);
            assertThat(asset.width()).isEqualTo(64);
            assertThat(asset.height()).isEqualTo(32);
            assertThat(asset.url()).isEqualTo("https://s3/get");
            assertThat(asset.urlExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("객체가 없으면 409 ASSET-001 이고 상태는 그대로다")
        void objectMissing() {
            given(fileStorageService.head(pending.getS3Key())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.complete(user.getPublicId(), "As8kLm2Pq9Xz"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AssetErrorCode.OBJECT_NOT_UPLOADED);
            assertThat(pending.getStatus()).isEqualTo(AssetStatus.PENDING_UPLOAD);
        }

        @Test
        @DisplayName("크기가 선언과 다르면 REJECTED + ASSET-002, 바이트는 내려받지 않는다")
        void sizeMismatch() {
            ReflectionTestUtils.setField(pending, "declaredSize", 100L);
            given(fileStorageService.head(pending.getS3Key()))
                    .willReturn(Optional.of(new StoredObject(101L, "image/png")));

            AssetService.CompleteResult result = service.complete(user.getPublicId(), "As8kLm2Pq9Xz");

            assertThat(result.rejection()).isEqualTo(AssetErrorCode.SIZE_MISMATCH);
            assertThat(pending.getStatus()).isEqualTo(AssetStatus.REJECTED);
            assertThat(pending.getRejectReason()).isEqualTo("ASSET-002");
            then(fileStorageService).should(never()).downloadBytes(anyString());
        }

        @Test
        @DisplayName("깨진 이미지면 REJECTED + ASSET-003, URL 은 없다")
        void invalidImage() {
            byte[] junk = "not an image".getBytes();
            ReflectionTestUtils.setField(pending, "declaredSize", (long) junk.length);
            given(fileStorageService.head(pending.getS3Key()))
                    .willReturn(Optional.of(new StoredObject(junk.length, "image/png")));
            given(fileStorageService.downloadBytes(pending.getS3Key())).willReturn(junk);

            AssetService.CompleteResult result = service.complete(user.getPublicId(), "As8kLm2Pq9Xz");

            assertThat(result.rejection()).isEqualTo(AssetErrorCode.INVALID_IMAGE);
            assertThat(result.asset().url()).isNull();
        }

        @Test
        @DisplayName("이미 판정이 끝났으면 S3 를 다시 보지 않고 그대로 돌려준다 — 멱등")
        void alreadyJudged() {
            pending.reject("ASSET-003");

            AssetService.CompleteResult result = service.complete(user.getPublicId(), "As8kLm2Pq9Xz");

            assertThat(result.rejection()).isEqualTo(AssetErrorCode.INVALID_IMAGE);
            then(fileStorageService).should(never()).head(anyString());
        }

        @Test
        @DisplayName("업로더도 공간 멤버도 아니면 404")
        void notAccessible() {
            User other = User.localUser("other@harucut.com", "encoded", "남");
            ReflectionTestUtils.setField(other, "id", 2L);
            given(userRepository.findByPublicId(other.getPublicId())).willReturn(Optional.of(other));
            given(workspaceService.isMember(10L, 2L)).willReturn(false);

            assertThatThrownBy(() -> service.complete(other.getPublicId(), "As8kLm2Pq9Xz"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
            then(fileStorageService).should(never()).head(anyString());
        }
    }

    @Nested
    @DisplayName("adopt — 예전 key 를 자산으로 편입(발행용)")
    class Adopt {

        private static final String KEY = "uploads/users/x/components/legacy.png";

        @Test
        @DisplayName("이미 VERIFIED 자산이면 S3 를 다시 보지 않고 그대로 쓴다")
        void existingVerified() {
            Asset verified = Asset.pendingUpload("As1", 10L, AssetPurpose.FRAME_ASSET, KEY, "image/png", 1L, 1L);
            verified.verify(1L, 1, 1, java.time.LocalDateTime.of(2026, 10, 26, 10, 0));
            given(assetRepository.findByS3Key(KEY)).willReturn(Optional.of(verified));

            AssetService.AdoptResult result = service.adopt(KEY, AssetPurpose.FRAME_ASSET, 10L, 1L);

            assertThat(result.rejection()).isNull();
            assertThat(result.asset()).isSameAs(verified);
            then(fileStorageService).should(never()).head(anyString());
        }

        @Test
        @DisplayName("처음이면 자산 행을 만들고 같은 검증으로 VERIFIED")
        void newAdoptionVerified() throws Exception {
            byte[] png = png(20, 10);
            given(assetRepository.findByS3Key(KEY)).willReturn(Optional.empty());
            given(fileStorageService.head(KEY)).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
            given(fileStorageService.downloadBytes(KEY)).willReturn(png);

            AssetService.AdoptResult result = service.adopt(KEY, AssetPurpose.FRAME_ASSET, 10L, 1L);

            assertThat(result.rejection()).isNull();
            assertThat(result.asset().getS3Key()).isEqualTo(KEY);
            assertThat(result.asset().getStatus()).isEqualTo(AssetStatus.VERIFIED);
            assertThat(result.asset().getWorkspaceId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("객체가 없으면 ASSET-001 거부 — 자산 행은 만들지 않는다")
        void missingObject() {
            given(assetRepository.findByS3Key(KEY)).willReturn(Optional.empty());
            given(fileStorageService.head(KEY)).willReturn(Optional.empty());

            AssetService.AdoptResult result = service.adopt(KEY, AssetPurpose.FRAME_ASSET, 10L, 1L);

            assertThat(result.rejection()).isEqualTo(AssetErrorCode.OBJECT_NOT_UPLOADED);
            then(assetRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Content-Type 이 없으면 확장자로 형식을 정해 검증한다")
        void mimeFromExtension() throws Exception {
            byte[] png = png(4, 4);
            given(assetRepository.findByS3Key(KEY)).willReturn(Optional.empty());
            given(fileStorageService.head(KEY)).willReturn(Optional.of(new StoredObject(png.length, null)));
            given(fileStorageService.downloadBytes(KEY)).willReturn(png);

            assertThat(service.adopt(KEY, AssetPurpose.FRAME_ASSET, 10L, 1L).asset().getContentType())
                    .isEqualTo("image/png");
        }
    }

    @Test
    @DisplayName("get: PENDING 이면 URL 없이 상태만")
    void getPendingHasNoUrl() {
        Asset pending = Asset.pendingUpload("As1", 10L, AssetPurpose.FRAME_ASSET, "k", "image/png", 1L, 1L);
        given(assetRepository.findByPublicId("As1")).willReturn(Optional.of(pending));

        AssetResponse response = service.get(user.getPublicId(), "As1");

        assertThat(response.status()).isEqualTo(AssetStatus.PENDING_UPLOAD);
        assertThat(response.url()).isNull();
        then(fileStorageService).should(never()).generatePresignedGetUrl(anyString(), any(Duration.class));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
