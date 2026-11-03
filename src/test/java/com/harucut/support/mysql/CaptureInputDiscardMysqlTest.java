package com.harucut.support.mysql;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.asset.service.AssetService;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.CaptureRun;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.CaptureRunRepository;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 부스 회차 닫기 ↔ 합성 접수 — 실제 MySQL. 처음 조회 때 묶이지 않았던 입력이 접수에 묶인 뒤 무조건 지워지면 접수된 작업의 입력이 사라진다.
 * 회차 정리는 잠금 읽기로 접수의 bindToJob 과 줄을 선다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("회차 입력 정리 ↔ 합성 접수 — 실제 MySQL 동시성")
class CaptureInputDiscardMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private AssetService assetService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private CaptureRunRepository captureRunRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventExperienceVersionRepository experienceVersionRepository;
    @Autowired private FrameService frameService;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    private TransactionTemplate transaction;
    private Long runId;
    private Long jobId;
    private Asset input;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User owner = userRepository.save(User.localUser("discard-" + unique + "@harucut.com", "e", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "공간");
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "discard-" + unique, null, null, null, null, null, null, null, null, null, null, null, null));
        Long eventId = eventRepository.findByPublicId(event.eventId()).orElseThrow().getId();
        Participant participant = participantRepository.save(Participant.byod(eventId));
        Long versionId = experienceVersionRepository.save(EventExperienceVersion.publish(eventId, 1, "{}", owner.getId())).getId();
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        runId = captureRunRepository.save(CaptureRun.start(participant.getId(), eventId, versionId, null, LocalDateTime.now().plusMinutes(30))).getId();
        jobId = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", frameId, "k-" + unique,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                eventId, runId, null, null)).getId();
        input = assetRepository.save(Asset.captureInput(Asset.newPublicId(), eventId, participant.getId(), runId, 0,
                "uploads/events/e/runs/r/inputs/" + unique + ".png", "image/png", 100));
    }

    private int deletionRows() {
        Integer rows = jdbcTemplate.queryForObject("select count(*) from s3_deletion where s3_key = ?", Integer.class, input.getS3Key());
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("접수가 먼저 입력을 묶는 중이면 정리는 커밋을 기다렸다가 묶인 입력을 지우지 않는다")
    void acceptFirst() throws Exception {
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> accept = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            assertThat(assetRepository.bindToJob(List.of(input.getId()), jobId)).isEqualTo(1);
            bound.countDown();
            await(release);
        }));
        assertThat(bound.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> discard = CompletableFuture.runAsync(() -> assetService.discardUnboundInputs(runId));
        Thread.sleep(1000);
        assertThat(discard).as("잠금 읽기가 접수의 커밋을 기다려야 한다").isNotDone();
        release.countDown();
        accept.get(20, TimeUnit.SECONDS);
        discard.get(20, TimeUnit.SECONDS);

        assertThat(assetRepository.findById(input.getId())).isPresent();
        assertThat(deletionRows()).isZero();
    }

    @Test
    @DisplayName("정리가 먼저면 접수의 bindToJob 이 지워진 행을 못 찾아 0 — 접수가 지고, 객체는 삭제 기록으로 지워진다")
    void discardFirst() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> discard = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            assetService.discardUnboundInputs(runId);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> accept = CompletableFuture.supplyAsync(() -> transaction.execute(status ->
                assetRepository.bindToJob(List.of(input.getId()), jobId)));
        Thread.sleep(1000);
        release.countDown();
        discard.get(20, TimeUnit.SECONDS);

        assertThat(accept.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(assetRepository.findById(input.getId())).isEmpty();
        assertThat(deletionRows()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
