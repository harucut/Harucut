package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.PageResponse;
import com.harucut.media.dto.UserMediaResponse;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.policy.MediaSubscriptionPolicy;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserMediaService")
class UserMediaServiceTest {

    private static final String PUBLIC_ID = "AbCdEf12Gh01";
    private static final Long MEDIA_ID = 7L;
    private static final String KEY = "uploads/users/AbCdEf12Gh01/fourcuts/photo.png";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 7, 20, 10, 0);
    private static final String SIGNED_URL = "https://harucut-test.s3.amazonaws.com/signed";
    private static final String VIEW_URL = "https://harucut-test.s3.amazonaws.com/view";
    private static final String THUMB_KEY = "uploads/users/AbCdEf12Gh01/fourcuts/photo-thumb.jpg";
    private static final String THUMB_URL = "https://harucut-test.s3.amazonaws.com/thumb";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMediaRepository userMediaRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private MediaSubscriptionPolicy mediaSubscriptionPolicy;

    @Mock
    private S3Deleter s3Deleter;

    private UserMediaService userMediaService;
    private User user;

    @BeforeEach
    void setUp() {
        userMediaService = new UserMediaService(userRepository, userMediaRepository,
                fileStorageService, mediaSubscriptionPolicy, s3Deleter);
        user = User.localUser("owner@harucut.com", "encoded", "소유자");
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("사용자가 없으면 GEN-031이다")
    void unknownUser() {
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userMediaService.getMyMedia(PUBLIC_ID, 0, 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    @Nested
    @DisplayName("getMyMedia")
    class GetMyMedia {

        @Test
        @DisplayName("cutoff가 없으면(무제한) 전체 쿼리를 쓰고 항목마다 보기·다운로드 URL이 둘 다 붙는다")
        void unlimitedUsesFullQuery() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(mediaSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(null);
            given(userMediaRepository.findAllByUserOrderByCreatedAtDesc(eq(user), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(media), PageRequest.of(0, 10), 1));
            given(fileStorageService.generatePresignedGetUrl(KEY)).willReturn(VIEW_URL);
            given(fileStorageService.generatePresignedDownloadUrl(KEY, "이름.png")).willReturn(SIGNED_URL);

            PageResponse<UserMediaResponse> result = userMediaService.getMyMedia(PUBLIC_ID, 0, 10);

            assertThat(result.content()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.viewUrl()).isEqualTo(VIEW_URL);
                        assertThat(item.downloadUrl()).isEqualTo(SIGNED_URL);
                        // 썸네일이 없는 행은 서명 시도조차 없어야 한다
                        assertThat(item.thumbnailUrl()).isNull();
                    });
            then(userMediaRepository).should(never())
                    .findAllByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any(), any(), any());
        }

        @Test
        @DisplayName("thumbnailKey가 있으면 thumbnailUrl도 plain GET으로 서명된다")
        void thumbnailSignedWhenPresent() {
            UserMedia media = mediaWithThumbnail();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(mediaSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(null);
            given(userMediaRepository.findAllByUserOrderByCreatedAtDesc(eq(user), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(media), PageRequest.of(0, 10), 1));
            given(fileStorageService.generatePresignedGetUrl(THUMB_KEY)).willReturn(THUMB_URL);
            given(fileStorageService.generatePresignedGetUrl(KEY)).willReturn(VIEW_URL);
            given(fileStorageService.generatePresignedDownloadUrl(KEY, "이름.png")).willReturn(SIGNED_URL);

            PageResponse<UserMediaResponse> result = userMediaService.getMyMedia(PUBLIC_ID, 0, 10);

            assertThat(result.content()).singleElement()
                    .satisfies(item -> assertThat(item.thumbnailUrl()).isEqualTo(THUMB_URL));
        }

        @Test
        @DisplayName("cutoff가 있으면 기간 쿼리를 쓰고 cutoff가 그대로 전달된다")
        void limitedUsesCutoffQuery() {
            LocalDateTime cutoff = LocalDateTime.of(2026, 7, 19, 10, 0);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(mediaSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(cutoff);
            given(userMediaRepository.findAllByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    eq(user), eq(cutoff), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

            PageResponse<UserMediaResponse> result = userMediaService.getMyMedia(PUBLIC_ID, 0, 10);

            assertThat(result.totalElements()).isZero();
            then(userMediaRepository).should(never()).findAllByUserOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("page가 음수면 GEN-002이고 쿼리는 나가지 않는다")
        void negativePageRejected() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> userMediaService.getMyMedia(PUBLIC_ID, -1, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);

            then(userMediaRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("getDownloadUrl")
    class GetDownloadUrl {

        @Test
        @DisplayName("내 미디어면 기간 검사를 거쳐 서명 URL을 돌려준다")
        void returnsSignedUrl() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));
            given(fileStorageService.generatePresignedDownloadUrl(KEY, "이름.png")).willReturn(SIGNED_URL);

            assertThat(userMediaService.getDownloadUrl(PUBLIC_ID, MEDIA_ID)).isEqualTo(SIGNED_URL);

            then(mediaSubscriptionPolicy).should().assertHistoryAccessible(1L, CREATED);
        }

        @Test
        @DisplayName("남의 것이든 없는 것이든 GEN-031이고 서명하지 않는다")
        void hiddenWhenNotMine() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.empty());

            assertThatThrownBy(() -> userMediaService.getDownloadUrl(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);

            then(fileStorageService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("기간이 지났으면 SUBS-002이고 서명하지 않는다")
        void retentionExpired() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(mediaSubscriptionPolicy).assertHistoryAccessible(1L, CREATED);

            assertThatThrownBy(() -> userMediaService.getDownloadUrl(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED);

            then(fileStorageService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("updateDisplayName")
    class UpdateDisplayName {

        @Test
        @DisplayName("정제기를 거친 이름으로 바뀐다 — 확장자는 key에서 붙는다")
        void renamesThroughSanitizer() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));
            given(fileStorageService.generatePresignedGetUrl(KEY)).willReturn(VIEW_URL);
            given(fileStorageService.generatePresignedDownloadUrl(KEY, "my_holiday_photo.png"))
                    .willReturn(SIGNED_URL);

            UserMediaResponse result =
                    userMediaService.updateDisplayName(PUBLIC_ID, MEDIA_ID, "my_holiday_photo");

            // 응답과 엔티티 둘 다 정제된 이름이고, 다운로드 URL도 새 이름으로 서명된다
            assertThat(result.displayName()).isEqualTo("my_holiday_photo.png");
            assertThat(media.getDisplayName()).isEqualTo("my_holiday_photo.png");
        }

        @Test
        @DisplayName("기간이 지났으면 SUBS-002이고 이름이 바뀌지 않는다")
        void retentionExpiredKeepsName() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(mediaSubscriptionPolicy).assertHistoryAccessible(1L, CREATED);

            assertThatThrownBy(() -> userMediaService.updateDisplayName(PUBLIC_ID, MEDIA_ID, "새이름"))
                    .isInstanceOf(BusinessException.class);

            assertThat(media.getDisplayName()).isEqualTo("이름.png");
        }
    }

    @Nested
    @DisplayName("deleteMedia")
    class DeleteMedia {

        @Test
        @DisplayName("S3 삭제를 예약한 다음 행을 지운다 — 기간 검사는 없다")
        void deletesWithS3Reservation() {
            UserMedia media = media();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));

            userMediaService.deleteMedia(PUBLIC_ID, MEDIA_ID);

            // 썸네일 없는 옛 행 — null이 리스트에 실려도 터지지 않아야 한다 (걸러내는 건 S3Deleter 몫)
            InOrder order = inOrder(s3Deleter, userMediaRepository);
            order.verify(s3Deleter).deleteAfterCommit(Arrays.asList(KEY, null), ComposeService.LATE_WRITE_WINDOW);
            order.verify(userMediaRepository).delete(media);
            then(mediaSubscriptionPolicy).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("썸네일이 있으면 원본과 썸네일 둘 다 삭제 예약된다")
        void deletesThumbnailToo() {
            UserMedia media = mediaWithThumbnail();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.of(media));

            userMediaService.deleteMedia(PUBLIC_ID, MEDIA_ID);

            then(s3Deleter).should().deleteAfterCommit(List.of(KEY, THUMB_KEY), ComposeService.LATE_WRITE_WINDOW);
        }

        @Test
        @DisplayName("남의 것이든 없는 것이든 GEN-031이고 아무것도 지워지지 않는다")
        void hiddenNothingDeleted() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, user)).willReturn(Optional.empty());

            assertThatThrownBy(() -> userMediaService.deleteMedia(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);

            then(s3Deleter).shouldHaveNoInteractions();
            then(userMediaRepository).should(never()).delete(any(UserMedia.class));
        }
    }

    // ── fixtures ──────────────────────────────

    private UserMedia media() {
        UserMedia media = UserMedia.of(user, KEY, "이름.png");
        ReflectionTestUtils.setField(media, "id", MEDIA_ID);
        ReflectionTestUtils.setField(media, "createdAt", CREATED);
        return media;
    }

    private UserMedia mediaWithThumbnail() {
        UserMedia media = UserMedia.of(user, KEY, THUMB_KEY, "이름.png");
        ReflectionTestUtils.setField(media, "id", MEDIA_ID);
        ReflectionTestUtils.setField(media, "createdAt", CREATED);
        return media;
    }
}
