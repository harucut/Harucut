package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.media.dto.SharedMediaResponse;
import com.harucut.media.entity.MediaShare;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.policy.MediaSubscriptionPolicy;
import com.harucut.media.repository.MediaShareRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaShareService — 사진 공유 링크")
class MediaShareServiceTest {

    private static final String PUBLIC_ID = "AbCdEf12Gh01";
    private static final Long MEDIA_ID = 7L;
    private static final String KEY = "uploads/users/AbCdEf12Gh01/fourcuts/job-7.jpg";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 15, 10, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final Duration TTL = Duration.ofMinutes(5);

    @Mock private UserRepository userRepository;
    @Mock private UserMediaRepository userMediaRepository;
    @Mock private MediaShareRepository mediaShareRepository;
    @Mock private MediaSubscriptionPolicy mediaSubscriptionPolicy;
    @Mock private FileStorageService fileStorageService;

    private MediaShareService service;
    private User owner;
    private UserMedia media;

    @BeforeEach
    void setUp() {
        service = new MediaShareService(userRepository, userMediaRepository, mediaShareRepository, mediaSubscriptionPolicy,
                fileStorageService, new StorageProperties(null, new StorageProperties.Download(TTL), null), CLOCK);
        owner = User.localUser("owner@harucut.com", "encoded", "주인");
        ReflectionTestUtils.setField(owner, "id", 1L);
        media = UserMedia.of(owner, KEY, "나의 기록.jpg");
        ReflectionTestUtils.setField(media, "id", MEDIA_ID);
        ReflectionTestUtils.setField(media, "createdAt", CREATED);
    }

    @Nested
    @DisplayName("link — 주인이 받는 토큰")
    class Link {

        @BeforeEach
        void ownedMedia() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(owner));
        }

        @Test
        @DisplayName("이미 있으면 그 토큰 그대로 — 새로 만들지 않는다")
        void returnsExistingToken() {
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, owner)).willReturn(Optional.of(media));
            given(mediaShareRepository.findByMediaId(MEDIA_ID)).willReturn(Optional.of(share(3L, "existing-token")));

            assertThat(service.link(PUBLIC_ID, MEDIA_ID).token()).isEqualTo("existing-token");

            then(mediaShareRepository).should(never()).insertIfAbsent(any(), any(), any());
        }

        @Test
        @DisplayName("없으면 넣고 잠금 읽기로 받는다 — 동시에 먼저 들어간 쪽이 있으면 그 토큰이다")
        void createsOnceAndReadsTheWinner() {
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, owner)).willReturn(Optional.of(media));
            given(mediaShareRepository.findByMediaId(MEDIA_ID)).willReturn(Optional.empty());
            given(mediaShareRepository.lockByMediaId(MEDIA_ID)).willReturn(Optional.of(share(3L, "winner-token")));

            assertThat(service.link(PUBLIC_ID, MEDIA_ID).token()).isEqualTo("winner-token");

            ArgumentCaptor<String> generated = ArgumentCaptor.forClass(String.class);
            then(mediaShareRepository).should().insertIfAbsent(eq(MEDIA_ID), generated.capture(), eq(LocalDateTime.now(CLOCK)));
            // CSPRNG 32바이트 base64url — 128비트 이상, URL 에 그대로 실린다
            assertThat(generated.getValue()).matches("[A-Za-z0-9_-]{43}");
        }

        @Test
        @DisplayName("남의 사진·없는 사진은 GEN-031 — 링크를 만들지 않는다")
        void notMine() {
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, owner)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.link(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
            then(mediaShareRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("보관 기간이 지난 사진은 SUBS-002 — 단건 조회와 같다")
        void retentionPassed() {
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, owner)).willReturn(Optional.of(media));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(mediaSubscriptionPolicy).assertHistoryAccessible(1L, CREATED);

            assertThatThrownBy(() -> service.link(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED);
            then(mediaShareRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("넣는 사이 사진이 지워졌으면(0행·잠금 읽기도 비었다) GEN-031")
        void mediaDeletedMeanwhile() {
            given(userMediaRepository.findByIdAndUser(MEDIA_ID, owner)).willReturn(Optional.of(media));
            given(mediaShareRepository.findByMediaId(MEDIA_ID)).willReturn(Optional.empty());
            given(mediaShareRepository.lockByMediaId(MEDIA_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.link(PUBLIC_ID, MEDIA_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("open — 받는 사람")
    class Open {

        private final MediaSharePrincipal principal = new MediaSharePrincipal(3L, MEDIA_ID);

        @Test
        @DisplayName("주인 기록에 보이면 이름·시각과 짧은 URL 둘(화면용 GET·저장용 attachment)")
        void opensWithShortUrls() {
            given(mediaShareRepository.findById(3L)).willReturn(Optional.of(share(3L, "t")));
            given(userMediaRepository.findById(MEDIA_ID)).willReturn(Optional.of(media));
            given(mediaSubscriptionPolicy.isHistoryAccessible(1L, CREATED)).willReturn(true);
            given(fileStorageService.generatePresignedGetUrl(KEY, TTL)).willReturn("https://s3/view");
            given(fileStorageService.generatePresignedDownloadUrl(KEY, "나의 기록.jpg", TTL)).willReturn("https://s3/save");

            SharedMediaResponse response = service.open(principal);

            assertThat(response.displayName()).isEqualTo("나의 기록.jpg");
            assertThat(response.createdAt()).isEqualTo(CREATED);
            assertThat(response.imageUrl()).isEqualTo("https://s3/view");
            assertThat(response.downloadUrl()).isEqualTo("https://s3/save");
        }

        @ParameterizedTest
        @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("주인이 탈퇴 요청·정지·탈퇴면 MEDIA-002 — 서명 URL 을 만들지 않는다")
        void closedUnlessOwnerActive(UserStatus status) {
            ReflectionTestUtils.setField(owner, "userStatus", status);
            given(mediaShareRepository.findById(3L)).willReturn(Optional.of(share(3L, "t")));
            given(userMediaRepository.findById(MEDIA_ID)).willReturn(Optional.of(media));

            assertClosed();
        }

        @Test
        @DisplayName("보관 기간이 지났으면 MEDIA-002")
        void closedAfterRetention() {
            given(mediaShareRepository.findById(3L)).willReturn(Optional.of(share(3L, "t")));
            given(userMediaRepository.findById(MEDIA_ID)).willReturn(Optional.of(media));
            given(mediaSubscriptionPolicy.isHistoryAccessible(1L, CREATED)).willReturn(false);

            assertClosed();
        }

        @Test
        @DisplayName("링크나 사진이 그사이 지워졌으면 MEDIA-002")
        void closedWhenGone() {
            given(mediaShareRepository.findById(3L)).willReturn(Optional.empty());
            assertClosed();

            given(mediaShareRepository.findById(3L)).willReturn(Optional.of(share(3L, "t")));
            given(userMediaRepository.findById(MEDIA_ID)).willReturn(Optional.empty());
            assertClosed();
        }

        private void assertClosed() {
            assertThatThrownBy(() -> service.open(principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(MediaErrorCode.SHARE_CLOSED);
            then(fileStorageService).should(never()).generatePresignedGetUrl(anyString(), any());
            then(fileStorageService).should(never()).generatePresignedDownloadUrl(anyString(), anyString(), any());
        }
    }

    private static MediaShare share(long id, String token) {
        MediaShare share = BeanUtils.instantiateClass(MediaShare.class);
        ReflectionTestUtils.setField(share, "id", id);
        ReflectionTestUtils.setField(share, "mediaId", MEDIA_ID);
        ReflectionTestUtils.setField(share, "token", token);
        return share;
    }
}
