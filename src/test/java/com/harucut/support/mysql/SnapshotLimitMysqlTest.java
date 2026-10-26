package com.harucut.support.mysql;

import com.harucut.booth.service.BoothDeviceService;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 「공간 잠금 뒤 세기」가 REPEATABLE READ 스냅샷에 속지 않는지 — 실제 MySQL.
 * 공간 행을 잠가 둔 채 두 요청을 출발시킨다. 둘 다 잠금 전에 일반 읽기로 스냅샷을 잡고 잠금에서 기다린다.
 * 풀면 앞쪽이 커밋하고, 뒤쪽은 그 커밋을 잠금 읽기로 봐야 한다(일반 읽기로 세면 예전 스냅샷이라 놓친다).
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("잠금 뒤 세는 상한 — 실제 MySQL 동시성")
class SnapshotLimitMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private EventService eventService;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("동시 운영 행사 수 1 — 동시에 두 행사를 게시해도 하나만 LIVE")
    void concurrentPublishRespectsMaxLiveEvents() throws Exception {
        User owner = userRepository.save(User.localUser("owner@live.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "공간");
        Long workspaceId = grant(org, new Contract.Terms(1000, null, null, 1, EnumSet.noneOf(Feature.class)));
        Long frameVersionId = frameVersionService.publish(owner.getPublicId(), frameService.createFrame(owner.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId())
                .frameVersionId();
        EventResponse first = ready(owner, org, "live-a", frameVersionId);
        EventResponse second = ready(owner, org, "live-b", frameVersionId);

        List<Throwable> failures = raceBehindWorkspaceLock(workspaceId,
                () -> eventService.publish(owner.getPublicId(), first.eventId()),
                () -> eventService.publish(owner.getPublicId(), second.eventId()));

        assertThat(eventRepository.findAll()).filteredOn(e -> e.getStatus() == EventStatus.LIVE).hasSize(1);
        assertThat(failures).hasSize(1);   // 하나는 게시 위반(EVENT-003)
    }

    @Test
    @DisplayName("부스 기기 수 1 — 동시에 두 대를 만들어도 한 대만")
    void concurrentDeviceCreateRespectsLimit() throws Exception {
        User owner = userRepository.save(User.localUser("owner@booth.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "부스 공간");
        Long workspaceId = grant(org, new Contract.Terms(1000, null, 1, null, EnumSet.noneOf(Feature.class)));
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("부스 행사", "booth-limit", null, null, null, null, null, null, null, null, null, null));

        List<Throwable> failures = raceBehindWorkspaceLock(workspaceId,
                () -> boothDeviceService.create(owner.getPublicId(), event.eventId(), "기기 1"),
                () -> boothDeviceService.create(owner.getPublicId(), event.eventId(), "기기 2"));

        assertThat(boothDeviceService.list(owner.getPublicId(), event.eventId())).hasSize(1);
        assertThat(failures).hasSize(1);   // 하나는 BOOTH 기기 상한
    }

    private Long grant(WorkspaceResponse org, Contract.Terms terms) {
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        LocalDateTime now = LocalDateTime.now();
        entitlementRepository.save(Entitlement.manual(workspaceId, null, terms, now.minusDays(1), now.plusYears(1), null, "테스트"));
        return workspaceId;
    }

    private EventResponse ready(User owner, WorkspaceResponse org, String slug, Long frameVersionId) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings(slug, slug, null, null, null, null, null, null, null, null, null, null));
        return eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
    }

    // 공간 행을 잠가 둔 채 두 요청을 출발시키고, 둘 다 잠금에서 기다릴 때 푼다. 실패한 요청의 예외 목록을 돌려준다
    private List<Throwable> raceBehindWorkspaceLock(Long workspaceId, Runnable a, Runnable b) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            workspaceRepository.lockById(workspaceId);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> attempt(a));
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> attempt(b));
        Thread.sleep(1000);   // 둘 다 스냅샷을 잡고 공간 잠금에서 기다리는 중
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        List<Throwable> failures = new ArrayList<>();
        for (CompletableFuture<Throwable> f : List.of(first, second)) {
            Throwable failure = f.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private static Throwable attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
