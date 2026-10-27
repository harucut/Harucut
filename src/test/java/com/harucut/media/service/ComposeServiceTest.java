package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.cell.FreeCellsGate;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.frame.service.FrameService;
import com.harucut.media.compose.ComposeMetrics;
import com.harucut.media.compose.ComposeOutputPolicy;
import com.harucut.media.compose.ComposeRequestedEvent;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.compose.ComposeSpecAssembler;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.media.dto.ComposeRequest;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.push.service.PushMessage;
import com.harucut.push.service.PushRequestedEvent;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComposeService")
class ComposeServiceTest {

    private static final String PUBLIC_ID = "AbCdEf12Gh01";
    private static final Long FRAME_ID = 7L;
    private static final Long JOB_ID = 5L;
    private static final String IDEM_KEY = "b7e2c1d0-idem";
    private static final List<String> SOURCE_KEYS = List.of(
            "uploads/users/AbCdEf12Gh01/fourcuts/sources/1.jpg",
            "uploads/users/AbCdEf12Gh01/fourcuts/sources/2.jpg",
            "uploads/users/AbCdEf12Gh01/fourcuts/sources/3.jpg",
            "uploads/users/AbCdEf12Gh01/fourcuts/sources/4.jpg");
    private static final String RESULT_KEY =
            "uploads/users/AbCdEf12Gh01/fourcuts/job-" + JOB_ID + ".jpg";
    private static final String THUMB_KEY =
            "uploads/users/AbCdEf12Gh01/fourcuts/job-" + JOB_ID + "-thumb.jpg";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ComposeJobRepository composeJobRepository;

    @Mock
    private UserMediaRepository userMediaRepository;

    @Mock
    private FrameService frameService;

    @Mock
    private ComposeSpecAssembler composeSpecAssembler;

    @Mock
    private S3Deleter s3Deleter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EntitlementService entitlementService;

    private ComposeService composeService;
    private User user;

    @BeforeEach
    void setUp() {
        // 2026-07-20T10:00 UTC 고정 — 완성본 기본 파일명(harucut_타임스탬프) 검증용
        Clock fixed = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);
        composeService = new ComposeService(userRepository, composeJobRepository,
                userMediaRepository, frameService, composeSpecAssembler, new FreeCellsGate(false, JsonMapper.builder().build()), s3Deleter,
                eventPublisher, new ComposeMetrics(new SimpleMeterRegistry()), fixed, JsonMapper.builder().build(),
                entitlementService, new ComposeOutputPolicy(1));
        user = User.localUser("owner@harucut.com", "encoded", "소유자");
        ReflectionTestUtils.setField(user, "id", 1L);
        // 결과 key 는 작업이 생성 때 고정한 storage_root(= 사용자 폴더)에서 나온다
        ReflectionTestUtils.setField(user, "publicId", PUBLIC_ID);
    }

    @Nested
    @DisplayName("requestCompose — 접수")
    class RequestCompose {

        @Test
        @DisplayName("정상 접수: Job을 저장하고 커밋 후 실행할 이벤트를 발행한다")
        void acceptsAndPublishes() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());
            Frame frame = frame();
            given(frameService.getComposableFrame(user, FRAME_ID)).willReturn(frame);
            ComposeSpec spec = spec();
            given(composeSpecAssembler.assemble(frame, null)).willReturn(spec);
            given(composeJobRepository.save(any())).willAnswer(invocation -> {
                ComposeJob job = invocation.getArgument(0);
                ReflectionTestUtils.setField(job, "id", JOB_ID);
                return job;
            });

            ComposeJobResponse response =
                    composeService.requestCompose(PUBLIC_ID, request(SOURCE_KEYS));

            assertThat(response.jobId()).isEqualTo(JOB_ID);
            assertThat(response.status()).isEqualTo(ComposeStatus.PENDING);
            ArgumentCaptor<ComposeRequestedEvent> captor = ArgumentCaptor.captor();
            then(eventPublisher).should().publishEvent(captor.capture());
            ComposeRequestedEvent event = captor.getValue();
            assertThat(event.jobId()).isEqualTo(JOB_ID);
            assertThat(event.resultKey()).isEqualTo(RESULT_KEY);
            assertThat(event.thumbnailKey()).isEqualTo(THUMB_KEY);
            assertThat(event.sourceKeys()).containsExactlyElementsOf(SOURCE_KEYS);
            assertThat(event.spec()).isEqualTo(spec);
            // 업로드 발급 때 남긴 24시간 정리 책임을 작업이 가져간다
            SOURCE_KEYS.forEach(key -> then(s3Deleter).should().release(key));
        }

        @Test
        @DisplayName("원본 key가 presigned URL로 와도 정규화된 key로 저장된다")
        void sourceUrlsNormalized() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());
            Frame frame = frame();
            given(frameService.getComposableFrame(user, FRAME_ID)).willReturn(frame);
            given(composeSpecAssembler.assemble(frame, null)).willReturn(spec());
            given(composeJobRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            List<String> urls = SOURCE_KEYS.stream()
                    .map(key -> "https://harucut-test.s3.amazonaws.com/" + key + "?X-Amz-Signature=x")
                    .toList();

            composeService.requestCompose(PUBLIC_ID, request(urls));

            ArgumentCaptor<ComposeJob> captor = ArgumentCaptor.captor();
            then(composeJobRepository).should().save(captor.capture());
            assertThat(captor.getValue().sourceKeys()).containsExactlyElementsOf(SOURCE_KEYS);
        }

        @Test
        @DisplayName("같은 멱등 key의 재요청은 기존 Job을 돌려주고 아무것도 새로 하지 않는다")
        void idempotentReplay() {
            givenUser();
            ComposeJob existing = doneJob(RESULT_KEY, 42L);
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.of(existing));

            ComposeJobResponse response =
                    composeService.requestCompose(PUBLIC_ID, request(SOURCE_KEYS));

            assertThat(response.status()).isEqualTo(ComposeStatus.DONE);
            assertThat(response.mediaId()).isEqualTo(42L);
            then(composeJobRepository).should(org.mockito.Mockito.never()).save(any());
            then(eventPublisher).shouldHaveNoInteractions();
            then(frameService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("같은 멱등 key 에 다른 원본이 오면 409 MEDIA-001 이다")
        void sameKeyDifferentSourcesConflicts() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.of(doneJob(RESULT_KEY, 42L)));
            List<String> others = List.of(SOURCE_KEYS.get(1), SOURCE_KEYS.get(0),
                    SOURCE_KEYS.get(2), SOURCE_KEYS.get(3));

            assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID, request(others)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MediaErrorCode.IDEMPOTENCY_KEY_REUSED);
            then(composeJobRepository).should(org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("같은 멱등 key 에 다른 프레임이 오면 409 다")
        void sameKeyDifferentFrameConflicts() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.of(job()));

            assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID,
                    new ComposeRequest(FRAME_ID + 1, SOURCE_KEYS, IDEM_KEY, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MediaErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        @Test
        @DisplayName("원본을 URL 로 감싸 보낸 재요청도 같은 요청으로 본다 — 정규화한 뒤 비교한다")
        void urlWrappedReplayMatches() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.of(job()));
            List<String> urls = SOURCE_KEYS.stream()
                    .map(key -> "https://harucut-test.s3.amazonaws.com/" + key + "?X-Amz-Signature=x")
                    .toList();

            assertThat(composeService.requestCompose(PUBLIC_ID, request(urls)).status())
                    .isEqualTo(ComposeStatus.PENDING);
        }

        @Test
        @DisplayName("배경색은 요청에 있을 때만 비교한다 — 같은 색은 재생, 다른 색은 409")
        void backgroundColorComparedOnlyWhenSent() {
            ComposeJob job = job();   // spec 배경 #FFE4E1

            assertThat(ComposeService.sameRequest(job, FRAME_ID, SOURCE_KEYS, null)).isTrue();
            assertThat(ComposeService.sameRequest(job, FRAME_ID, SOURCE_KEYS, "#ffe4e1")).isTrue();
            assertThat(ComposeService.sameRequest(job, FRAME_ID, SOURCE_KEYS, "#000000")).isFalse();
        }

        @Test
        @DisplayName("동시 중복의 패자는 승자의 Job 을 다시 읽어 돌려받는다")
        void replayAfterConflictReturnsWinner() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.of(job()));

            assertThat(composeService.replayAfterConflict(PUBLIC_ID, request(SOURCE_KEYS)).jobId())
                    .isEqualTo(JOB_ID);
        }

        @Test
        @DisplayName("동시 중복인데 승자가 없으면 null — 다른 제약 위반이라 호출자가 원래 예외를 던진다")
        void replayAfterConflictWithoutWinner() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());

            assertThat(composeService.replayAfterConflict(PUBLIC_ID, request(SOURCE_KEYS))).isNull();
        }

        @Test
        @DisplayName("남의 원본 key는 403 GEN-021이고 Job이 만들어지지 않는다")
        void othersSourceKeyForbidden() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());
            List<String> othersKeys = List.of(
                    "uploads/users/SomeoneElse1/fourcuts/sources/1.jpg",
                    SOURCE_KEYS.get(1), SOURCE_KEYS.get(2), SOURCE_KEYS.get(3));

            assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID, request(othersKeys)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.FORBIDDEN);

            then(composeJobRepository).should(org.mockito.Mockito.never()).save(any());
            then(eventPublisher).shouldHaveNoInteractions();
        }

        // 합성에 성공하면 sourceKeys 가 전부 삭제된다(completeJob). 내 폴더라는 것만 확인하면
        // 사용자가 자기 프로필 사진·스티커 key 를 원본 자리에 넣어 자기 자산을 지울 수 있다 —
        // 그 스티커를 쓰는 프레임의 이후 합성이 전부 영구 실패한다
        @Test
        @DisplayName("내 key라도 원본 폴더 밖이면 403이다 — 자기 자산 자폭 차단")
        void ownKeyOutsideSourceFolderForbidden() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());
            List<String> myOtherFolderKeys = List.of(
                    "uploads/users/" + PUBLIC_ID + "/components/sticker.png",
                    SOURCE_KEYS.get(1), SOURCE_KEYS.get(2), SOURCE_KEYS.get(3));

            assertThatThrownBy(() ->
                    composeService.requestCompose(PUBLIC_ID, request(myOtherFolderKeys)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.FORBIDDEN);

            then(composeJobRepository).should(org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("프레임 관문의 404가 그대로 전파된다 — 남의/없는 프레임")
        void framGatePropagates() {
            givenUser();
            given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY))
                    .willReturn(Optional.empty());
            given(frameService.getComposableFrame(user, FRAME_ID))
                    .willThrow(new BusinessException(GlobalErrorCode.NOT_FOUND));

            assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID, request(SOURCE_KEYS)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("completeJob / failJob — 실행 결과 기록")
    class RecordResult {

        @Test
        @DisplayName("완료: 미디어를 만들고(기본 파일명), Job을 DONE으로, 원본 삭제를 예약한다")
        void completeCreatesMediaAndDeletesSources() {
            ComposeJob job = job();
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job));
            given(composeJobRepository.markDone(JOB_ID, RESULT_KEY, null, null,
                    ComposeStatus.PENDING, ComposeStatus.DONE)).willReturn(1);
            given(userMediaRepository.save(any())).willAnswer(invocation -> {
                UserMedia media = invocation.getArgument(0);
                ReflectionTestUtils.setField(media, "id", 42L);
                return media;
            });

            assertThat(composeService.completeJob(JOB_ID, RESULT_KEY, THUMB_KEY)).isTrue();

            ArgumentCaptor<UserMedia> captor = ArgumentCaptor.captor();
            then(userMediaRepository).should().save(captor.capture());
            assertThat(captor.getValue().getS3Key()).isEqualTo(RESULT_KEY);
            assertThat(captor.getValue().getThumbnailKey()).isEqualTo(THUMB_KEY);
            // 확장자는 s3Key에서 파생된다(DisplayNames) — 결과물이 .jpg가 되면 표시 이름도 따라온다
            assertThat(captor.getValue().getDisplayName()).isEqualTo("harucut_20260720_100000.jpg");
            then(composeJobRepository).should().attachMedia(JOB_ID, 42L);
            then(s3Deleter).should().deleteAfterCommit(SOURCE_KEYS);
            // 개인 작업 완료 푸시 — 전이를 이긴 이 트랜잭션에서만
            then(eventPublisher).should().publishEvent(new PushRequestedEvent(1L, PushMessage.composeDone(JOB_ID)));
        }

        @Test
        @DisplayName("읽은 뒤 다른 통지가 먼저 전이했으면(UPDATE 0행) 미디어도 삭제도 없다")
        void completeLosesRace() {
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job()));
            given(composeJobRepository.markDone(JOB_ID, RESULT_KEY, null, null,
                    ComposeStatus.PENDING, ComposeStatus.DONE)).willReturn(0);

            assertThat(composeService.completeJob(JOB_ID, RESULT_KEY, THUMB_KEY)).isFalse();

            then(userMediaRepository).shouldHaveNoInteractions();
            then(s3Deleter).shouldHaveNoInteractions();
            then(eventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("이미 끝난 Job의 완료 호출은 아무것도 안 한다 — 재실행 경합 보호")
        void completeIgnoredWhenNotPending() {
            given(composeJobRepository.findById(JOB_ID))
                    .willReturn(Optional.of(failedJob("먼저 실패로 기록됨")));

            assertThat(composeService.completeJob(JOB_ID, RESULT_KEY, THUMB_KEY)).isFalse();

            then(userMediaRepository).shouldHaveNoInteractions();
            then(s3Deleter).shouldHaveNoInteractions();
            then(composeJobRepository).should(org.mockito.Mockito.never())
                    .markDone(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("실패 기록: FAILED 로 전이하고 원본 삭제를 예약한다. 없는 Job이면 조용히 지나간다")
        void failRecordsReasonAndDeletesSources() {
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job()));
            given(composeJobRepository.markFailed(JOB_ID, "원본을 읽을 수 없다", ComposeFailureCode.UNKNOWN, null,
                    ComposeStatus.PENDING, ComposeStatus.FAILED)).willReturn(1);
            given(composeJobRepository.findById(999L)).willReturn(Optional.empty());

            assertThat(composeService.failJob(JOB_ID, "원본을 읽을 수 없다")).isTrue();
            then(s3Deleter).should().deleteAfterCommit(SOURCE_KEYS);
            // 실패한 작업의 결과·썸네일 자리는 지금 한 번, 늦은 실행이 쓸 수 있는 기간 뒤 한 번 더 지운다(R5 D09)
            then(s3Deleter).should().deleteAfterCommit(List.of(RESULT_KEY, THUMB_KEY), ComposeService.LATE_WRITE_WINDOW);
            then(eventPublisher).should().publishEvent(
                    new PushRequestedEvent(1L, PushMessage.composeFailed(JOB_ID, "UNKNOWN")));

            assertThat(composeService.failJob(999L, "이유")).isFalse();
        }

        @Test
        @DisplayName("지난 시도의 실패(시도 번호 불일치 → UPDATE 0행)는 반영하지 않는다 — 원본도 결과 자리도 건드리지 않는다")
        void staleAttemptFailureIgnored() {
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job()));
            given(composeJobRepository.markFailed(JOB_ID, "늦은 1차 실패", ComposeFailureCode.RENDER_FAILED, 1,
                    ComposeStatus.PENDING, ComposeStatus.FAILED)).willReturn(0);

            assertThat(composeService.failJob(JOB_ID, "늦은 1차 실패", ComposeFailureCode.RENDER_FAILED, 1)).isFalse();
            then(s3Deleter).shouldHaveNoInteractions();
            then(eventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("출력 계약 2 작업은 검증한 체크섬 쌍 없이 완료하지 않는다")
        void verifiedContractRequiresChecksums() {
            ComposeJob job = job();
            job.fixRenderContract(2);
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job));

            assertThatThrownBy(() -> composeService.completeJob(JOB_ID, RESULT_KEY, THUMB_KEY))
                    .isInstanceOf(IllegalStateException.class);
            then(composeJobRepository).should(org.mockito.Mockito.never()).markDone(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("긴 사유는 255자로 잘려 UPDATE 된다")
        void failTruncatesReason() {
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job()));
            given(composeJobRepository.markFailed(any(), any(), any(), any(), any(), any())).willReturn(1);

            composeService.failJob(JOB_ID, "x".repeat(300));

            then(composeJobRepository).should().markFailed(JOB_ID, "x".repeat(255), ComposeFailureCode.UNKNOWN, null,
                    ComposeStatus.PENDING, ComposeStatus.FAILED);
        }

        @Test
        @DisplayName("이미 끝난 Job 의 실패(UPDATE 0행)는 원본을 지우지 않는다 — 늦은 실패가 성공 뒤를 건드리지 않는다")
        void failLosesRace() {
            given(composeJobRepository.findById(JOB_ID)).willReturn(Optional.of(job()));
            given(composeJobRepository.markFailed(any(), any(), any(), any(), any(), any())).willReturn(0);

            assertThat(composeService.failJob(JOB_ID, "뒤늦은 실패")).isFalse();
            then(s3Deleter).shouldHaveNoInteractions();
            then(eventPublisher).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("getJob — 폴링")
    class GetJob {

        @Test
        @DisplayName("남의/없는 Job은 404 GEN-031이다")
        void hiddenJob() {
            givenUser();
            given(composeJobRepository.findByIdAndUser(JOB_ID, user)).willReturn(Optional.empty());

            assertThatThrownBy(() -> composeService.getJob(PUBLIC_ID, JOB_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("실패한 Job은 사유가 실려 나간다")
        void failedJobCarriesReason() {
            givenUser();
            given(composeJobRepository.findByIdAndUser(JOB_ID, user))
                    .willReturn(Optional.of(failedJob("원본 유실")));

            ComposeJobResponse response = composeService.getJob(PUBLIC_ID, JOB_ID);

            assertThat(response.status()).isEqualTo(ComposeStatus.FAILED);
            assertThat(response.failureReason()).isEqualTo("원본 유실");
        }
    }

    @Test
    @DisplayName("합성 스펙이 spec_json(8000자)을 넘으면 저장 전에 400 FRAME-004 — DB 오류(500)가 아니다")
    void specTooLargeRejectedBeforeSave() {
        givenUser();
        given(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:1", IDEM_KEY)).willReturn(Optional.empty());
        Frame frame = frame();
        given(frameService.getComposableFrame(user, FRAME_ID)).willReturn(frame);
        List<ComposeSpec.Layer> layers = java.util.stream.IntStream.range(0, 80)
                .mapToObj(i -> new ComposeSpec.Layer("uploads/users/abc/components/layer-" + i + ".png",
                        1.0, 2.0, 3.0, 4.0, 1.0, 0.0, i, 1.0))
                .toList();
        given(composeSpecAssembler.assemble(frame, null)).willReturn(new ComposeSpec(2000, 6000,
                new BackgroundAttributes.Color("#FFE4E1"), FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false), layers));

        assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID, request(SOURCE_KEYS)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(com.harucut.frame.exception.FrameErrorCode.SPEC_TOO_LARGE);
        then(composeJobRepository).should(org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("사용자가 없으면 GEN-031이다")
    void unknownUser() {
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> composeService.requestCompose(PUBLIC_ID, request(SOURCE_KEYS)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    // ── fixtures ──────────────────────────────

    private void givenUser() {
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
    }

    private ComposeJob job() {
        ComposeJob job = ComposeJob.create(user, FRAME_ID, IDEM_KEY, SOURCE_KEYS, spec());
        ReflectionTestUtils.setField(job, "id", JOB_ID);
        return job;
    }

    // 전이는 조건부 UPDATE 라 엔티티 메서드가 없다 — 끝난 Job 은 필드를 직접 채워 만든다
    private ComposeJob doneJob(String resultKey, Long mediaId) {
        ComposeJob job = job();
        ReflectionTestUtils.setField(job, "status", ComposeStatus.DONE);
        ReflectionTestUtils.setField(job, "resultKey", resultKey);
        ReflectionTestUtils.setField(job, "mediaId", mediaId);
        return job;
    }

    private ComposeJob failedJob(String reason) {
        ComposeJob job = job();
        ReflectionTestUtils.setField(job, "status", ComposeStatus.FAILED);
        ReflectionTestUtils.setField(job, "failureReason", reason);
        return job;
    }

    private static ComposeRequest request(List<String> sourceKeys) {
        return new ComposeRequest(FRAME_ID, sourceKeys, IDEM_KEY, null);
    }

    private Frame frame() {
        Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC,
                new BackgroundAttributes.Color("#FFE4E1"));
        ReflectionTestUtils.setField(frame, "id", FRAME_ID);
        return frame;
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000,
                new BackgroundAttributes.Color("#FFE4E1"),
                FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false),
                List.of());
    }
}
