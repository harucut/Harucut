package com.harucut.event.photobox;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.dto.PhotoDtos;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.result.dto.ResultTokenResponse;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.result.repository.ResultAccessRepository;
import com.harucut.result.service.ResultPrincipal;
import com.harucut.result.service.ResultService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * P2B-2 — 행사 사진함(목록·커서·필터·다운로드·삭제)과 행사 복제를 실제 JPA 로 본다(ADR-0008 §3).
 * 합성 작업은 참가 흐름 대신 저장소로 바로 만든다 — 사진함은 결과 행만 읽는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("행사 사진함 — 통합")
class PhotoBoxIntegrationTest {

    @Autowired private PhotoService photoService;
    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private EventRepository eventRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private ResultService resultService;
    @Autowired private ResultAccessRepository resultAccessRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private ComposeService composeService;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private User owner;
    private User customer;
    private User stranger;
    private Event event;
    private ComposeJob byodDone;
    private ComposeJob boothDone;
    private ComposeJob failed;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willAnswer(i -> "https://s3/thumb/" + i.getArgument(0));
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/download");

        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        customer = userRepository.save(User.localUser("customer@harucut.com", "e", "신부"));
        stranger = userRepository.save(User.localUser("stranger@harucut.com", "e", "남"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "photo-box", null, null, null, null, null, null, null, null, null, null, null, null));
        event = eventRepository.findByPublicId(created.eventId()).orElseThrow();

        Participant byod = participantRepository.save(Participant.byod(event.getId()));
        Participant booth = participantRepository.save(Participant.booth(event.getId(), null));
        byodDone = job(byod, "k1", 41L);
        boothDone = job(booth, "k2", 42L);
        failed = job(byod, "k3", 41L);
        composeJobRepository.markDone(byodDone.getId(), "uploads/events/e/results/1.jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        composeJobRepository.markDone(boothDone.getId(), "uploads/events/e/results/2.jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        composeJobRepository.markFailed(failed.getId(), "S3 NoSuchKey: input missing", ComposeStatus.PENDING, ComposeStatus.FAILED);
        // 생성 순서를 시각으로 고정한다(같은 마이크로초에 만들어져도 커서 정렬이 흔들리지 않게)
        entityManager.flush();
        age(byodDone, 3);
        age(boothDone, 2);
        age(failed, 1);
        entityManager.clear();
    }

    @Test
    @DisplayName("목록: 최신순 커서로 끝까지, 썸네일은 DONE 만, 실패는 코드만, 필터(참가 방식·상태·프레임)")
    void pageAndFilters() {
        PhotoDtos.PhotoPage first = page(owner, null, 2, null, null, null);
        assertThat(first.items()).extracting(PhotoDtos.Photo::photoId).containsExactly(failed.getId(), boothDone.getId());
        assertThat(first.items().get(0).failure().code()).isEqualTo("INPUT_MISSING");
        assertThat(first.items().get(0).thumbnailUrl()).isNull();
        assertThat(first.items().get(1).thumbnailUrl()).startsWith("https://s3/thumb/");
        assertThat(first.items().get(1).channel()).isEqualTo(ParticipantChannel.BOOTH);

        PhotoDtos.PhotoPage second = page(owner, first.nextCursor(), 2, null, null, null);
        assertThat(second.items()).extracting(PhotoDtos.Photo::photoId).containsExactly(byodDone.getId());
        assertThat(second.nextCursor()).isNull();

        assertThat(page(owner, null, null, ParticipantChannel.BOOTH, null, null).items())
                .extracting(PhotoDtos.Photo::photoId).containsExactly(boothDone.getId());
        assertThat(page(owner, null, null, null, ComposeStatus.DONE, 41L).items())
                .extracting(PhotoDtos.Photo::photoId).containsExactly(byodDone.getId());
        assertCode(() -> page(owner, "not-a-cursor", null, null, null, null), GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("권한: 고객은 받은 권한만(열람만이면 다운로드 403 PHOTO-001), 관계없는 사람은 404")
    void permissions() {
        invitationService.accept(customer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken());

        assertThat(page(customer, null, null, null, null, null).items()).hasSize(3);
        assertCode(() -> photoService.download(customer.getPublicId(), event.getPublicId(), byodDone.getId()),
                PhotoErrorCode.PERMISSION_REQUIRED);
        assertCode(() -> photoService.delete(customer.getPublicId(), event.getPublicId(), byodDone.getId()),
                PhotoErrorCode.PERMISSION_REQUIRED);
        assertCode(() -> page(stranger, null, null, null, null, null), GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("다운로드: 5분 URL 에 「{행사명}-{photoId}.jpg」, 준비 안 된 사진은 RESULT-003")
    void download() {
        assertThat(photoService.download(owner.getPublicId(), event.getPublicId(), byodDone.getId()).url()).isEqualTo("https://s3/download");
        then(fileStorageService).should().generatePresignedDownloadUrl(eq("uploads/events/e/results/1.jpg"),
                eq("민지 ♥ 준호-" + byodDone.getId() + ".jpg"), eq(Duration.ofMinutes(5)));
        assertCode(() -> photoService.download(owner.getPublicId(), event.getPublicId(), failed.getId()),
                ResultErrorCode.NOT_READY);
    }

    @Test
    @DisplayName("삭제: 목록에서 빠지고 결과·썸네일 삭제 예약, 참가자 결과 링크는 EXPIRED")
    @SuppressWarnings("unchecked")
    void delete() {
        ResultTokenResponse token = resultService.issue(composeJobRepository.findById(byodDone.getId()).orElseThrow(), event);
        Long accessId = resultAccessRepository.findAll().stream()
                .filter(a -> a.getComposeJobId().equals(byodDone.getId())).findFirst().orElseThrow().getId();
        assertThat(token.resultToken()).isNotBlank();

        photoService.delete(owner.getPublicId(), event.getPublicId(), byodDone.getId());
        entityManager.clear();   // 삭제 표시는 벌크 UPDATE 다 — 운영에서는 다음 요청이 새로 읽는다

        assertThat(page(owner, null, null, null, null, null).items()).extracting(PhotoDtos.Photo::photoId)
                .doesNotContain(byodDone.getId());
        then(s3Deleter).should().deleteAfterCommit((Collection<String>) argThat(keys ->
                ((Collection<String>) keys).contains("uploads/events/e/results/1.jpg") && ((Collection<String>) keys).size() == 2));
        assertThat(resultService.status(new ResultPrincipal(accessId, byodDone.getId())).status()).isEqualTo("EXPIRED");
        assertCode(() -> photoService.delete(owner.getPublicId(), event.getPublicId(), byodDone.getId()), GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("합성 중(PENDING)에 지운 사진이 나중에 완료되면 완료 쪽이 결과·썸네일을 지운다")
    void deletedWhilePendingCleanedOnCompletion() {
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob pending = job(participant, "k-pending", 41L);
        entityManager.flush();
        entityManager.clear();

        photoService.delete(owner.getPublicId(), event.getPublicId(), pending.getId());
        then(s3Deleter).should(never()).deleteAfterCommit(any());   // 아직 결과가 없다

        assertThat(composeService.completeJob(pending.getId(), "uploads/events/e/results/late.jpg", "uploads/events/e/results/late_thumb.jpg"))
                .isTrue();
        then(s3Deleter).should().deleteAfterCommit(List.of("uploads/events/e/results/late.jpg", "uploads/events/e/results/late_thumb.jpg"));
    }

    @Test
    @DisplayName("복제: 설정 초안·참가 방식만 새 DRAFT, 복제한 사람에게 권한, slug 중복은 EVENT-004")
    void cloneEvent() {
        EventResponse copy = eventService.cloneEvent(owner.getPublicId(), event.getPublicId(), "다음 결혼식", "photo-box-2");

        assertThat(copy.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(copy.settings().name()).isEqualTo("다음 결혼식");
        assertThat(eventGrantService.myEvents(owner.getPublicId())).extracting(MemberDtos.MyEvent::eventId)
                .contains(copy.eventId());
        assertThat(page(owner, null, null, null, null, null).items()).hasSize(3);   // 원본 사진함은 그대로
        assertThat(photoService.page(owner.getPublicId(), copy.eventId(), null, null, null, null, null, null, null).items())
                .isEmpty();
        assertCode(() -> eventService.cloneEvent(owner.getPublicId(), event.getPublicId(), "x", "photo-box-2"),
                EventErrorCode.SLUG_TAKEN);
    }

    // ── helpers ──────────────────────────────

    private ComposeJob job(Participant participant, String key, Long frameVersionId) {
        return composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, frameVersionId));
    }

    private void age(ComposeJob job, int minutesAgo) {
        jdbcTemplate.update("update compose_job set created_at = ? where compose_job_id = ?",
                LocalDateTime.now().minusMinutes(minutesAgo), job.getId());
    }

    private PhotoDtos.PhotoPage page(User user, String cursor, Integer limit, ParticipantChannel channel,
                                     ComposeStatus status, Long frameVersionId) {
        return photoService.page(user.getPublicId(), event.getPublicId(), cursor, limit, null, null, channel, frameVersionId, status);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
