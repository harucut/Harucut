package com.harucut.event.retention;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.batch.EventRetentionService;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.event.service.PublicEventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.entity.ParticipantSession;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.result.entity.ResultAccess;
import com.harucut.result.repository.ResultAccessRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;

/*
 * P2B-6 — 행사 결과 보관 종료 배치를 실제 JPA 로 본다(ADR-0008 §7).
 * 테스트 트랜잭션 안이라 단계별 트랜잭션이 하나로 합쳐진다 — 커밋 후 S3 삭제는 예약(deleteAfterCommit)으로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("행사 보관 종료 — 통합")
class EventRetentionIntegrationTest {

    @Autowired private EventRetentionService retentionService;
    @Autowired private EventService eventService;
    @Autowired private PhotoService photoService;
    @Autowired private PublicEventService publicEventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ParticipantSessionRepository sessionRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private ResultAccessRepository resultAccessRepository;
    @Autowired private BoothDeviceRepository boothDeviceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private User owner;
    private Event event;
    private Participant participant;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        event = publishedEvent(owner, "retention");
        participant = participantRepository.save(Participant.byod(event.getId()));
    }

    @Test
    @DisplayName("기한 지난 결과만 정리(객체 삭제 예약·purged_at), 사진함에서 빠지고 최근 결과는 남는다")
    void purgesExpiredResults() {
        ComposeJob old = done("old", "uploads/r/old.jpg", 2);
        ComposeJob recent = done("recent", "uploads/r/recent.jpg", 0);

        EventRetentionService.Result result = retentionService.run();

        assertThat(result.purgedResults()).isEqualTo(1);
        // 정리 때 한 번, 같은 실행의 재삭제 단계에서 한 번 더(커밋 뒤 삭제가 빠졌을 때를 메우는 멱등 재시도)
        then(s3Deleter).should(times(2)).deleteAfterCommit(List.of("uploads/r/old.jpg",
                ComposeService.thumbnailKeyFor(composeJobRepository.findById(old.getId()).orElseThrow())));
        assertThat(composeJobRepository.findById(old.getId()).orElseThrow().getPurgedAt()).isNotNull();
        assertThat(photoService.page(owner.getPublicId(), event.getPublicId(), null, null, null, null, null, null, null).items())
                .extracting(p -> p.photoId()).containsExactly(recent.getId());
        assertThat(result.archivedEvents()).isZero();   // 아직 촬영 중
    }

    @Test
    @DisplayName("결과가 확정된 작업의 촬영 입력 행만 지운다 — 진행 중 작업의 입력은 남긴다")
    void deletesSettledCaptureInputs() {
        ComposeJob settled = done("s", "uploads/r/s.jpg", 0);
        ComposeJob pending = job("p");
        Asset settledInput = input("a.png", settled);
        Asset pendingInput = input("b.png", pending);

        assertThat(retentionService.run().deletedCaptureInputs()).isEqualTo(1);
        assertThat(assetRepository.findById(settledInput.getId())).isEmpty();
        assertThat(assetRepository.findById(pendingInput.getId())).isPresent();
    }

    @Test
    @DisplayName("촬영 종료 + 보일 결과 없음 → ARCHIVED(공개 404), 30일 뒤 세션·결과 토큰 삭제와 부스 기기 회수")
    void archiveAndCleanup() {
        ComposeJob old = done("old", "uploads/r/old.jpg", 2);
        resultAccessRepository.save(new ResultAccess("a".repeat(64), old.getId(), LocalDateTime.now().plusDays(1)));
        ParticipantSession session = sessionRepository.save(
                ParticipantSession.restricted(participant.getId(), "b".repeat(64), LocalDateTime.now().plusHours(1)));
        BoothDevice device = boothDeviceRepository.save(BoothDevice.pending(event.getWorkspaceId(), event.getId(), "부스"));
        eventService.closeCapture(owner.getPublicId(), event.getPublicId());

        EventRetentionService.Result first = retentionService.run();
        assertThat(first.archivedEvents()).isEqualTo(1);
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.ARCHIVED);
        assertThatThrownBy(() -> publicEventService.getBySlug("retention"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
        assertThat(first.cleanedEvents()).isZero();   // 30일 전

        entityManager.flush();
        jdbcTemplate.update("update event set archived_at = ? where event_id = ?", LocalDateTime.now().minusDays(31), event.getId());
        entityManager.clear();
        assertThat(retentionService.run().cleanedEvents()).isEqualTo(1);
        assertThat(sessionRepository.findById(session.getId())).isEmpty();
        assertThat(resultAccessRepository.findAll()).isEmpty();
        assertThat(boothDeviceRepository.findById(device.getId()).orElseThrow().isRevoked()).isTrue();
    }

    @Test
    @DisplayName("한 행사에서 정리가 실패해도 다른 행사는 계속 정리된다 — 실행 전체가 멈추지 않는다")
    void oneEventFailureDoesNotStopOthers() {
        done("first", "uploads/r/first.jpg", 2);
        Event second = publishedEvent(userRepository.save(User.localUser("other@harucut.com", "e", "다른 운영자")), "retention-2");
        Participant otherParticipant = participantRepository.save(Participant.byod(second.getId()));
        ComposeJob other = composeJobRepository.save(ComposeJob.forParticipant(otherParticipant.getId(), "uploads/events/e2/", 1L,
                "other", List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                second.getId(), null, null, 1L));
        composeJobRepository.markDone(other.getId(), "uploads/r/other.jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        entityManager.flush();
        jdbcTemplate.update("update compose_job set created_at = ? where compose_job_id = ?",
                LocalDateTime.now().minusDays(2).minusMinutes(1), other.getId());
        entityManager.clear();
        // 첫 번째로 정리하는 행사의 삭제 예약만 실패한다
        willThrow(new IllegalStateException("s3 queue down")).willDoNothing().given(s3Deleter).deleteAfterCommit(anyCollection());

        EventRetentionService.Result result = retentionService.run();

        assertThat(result.purgedResults()).isEqualTo(1);   // 실패한 행사는 0, 다른 행사는 1
        then(s3Deleter).should(atLeast(2)).deleteAfterCommit(anyCollection());
    }

    // 보관 1일·게시된 행사 — 공간마다 운영자와 이용권을 따로 만든다
    private Event publishedEvent(User owner, String slug) {
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        Long frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "resultRetentionDays", 1,
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        eventService.publish(owner.getPublicId(), created.eventId());
        return eventRepository.findByPublicId(created.eventId()).orElseThrow();
    }

    // ── helpers ──────────────────────────────

    private ComposeJob job(String key) {
        return composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, 1L));
    }

    private ComposeJob done(String key, String resultKey, int daysAgo) {
        ComposeJob job = job(key);
        composeJobRepository.markDone(job.getId(), resultKey, ComposeStatus.PENDING, ComposeStatus.DONE);
        entityManager.flush();
        jdbcTemplate.update("update compose_job set created_at = ? where compose_job_id = ?",
                LocalDateTime.now().minusDays(daysAgo).minusMinutes(1), job.getId());
        entityManager.clear();
        return job;
    }

    private Asset input(String name, ComposeJob job) {
        Asset asset = assetRepository.save(Asset.captureInput(Asset.newPublicId(), event.getId(), participant.getId(), null, 0,
                "uploads/events/e/runs/r/inputs/" + name, "image/png", 100));
        entityManager.flush();
        jdbcTemplate.update("update asset set bound_compose_job_id = ? where asset_id = ?", job.getId(), asset.getId());
        entityManager.clear();
        return asset;
    }
}
