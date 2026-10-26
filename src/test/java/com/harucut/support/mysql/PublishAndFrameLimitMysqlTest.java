package com.harucut.support.mysql;

import com.harucut.common.exception.BusinessException;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.exception.EventErrorCode;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.Workspace;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 같은 행사 동시 게시와 공간 프레임 개수 상한 — 실제 MySQL 동시성.
 * 잠글 행을 먼저 잡아 둔 채 두 요청을 출발시켜, 둘 다 진행한 뒤 잠금에서 기다리게 하고 푼다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql",
        "frame.limits.max-frames-per-workspace=2"
})
@ActiveProfiles("test")
@DisplayName("동시 게시·공간 프레임 상한 — 실제 MySQL 동시성")
class PublishAndFrameLimitMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameRepository frameRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("같은 행사를 동시에 두 번 게시하면 하나는 게시되고 다른 하나는 409 EVENT-005(500 이 아니다)")
    void concurrentPublishOfSameEvent() throws Exception {
        User owner = userRepository.save(User.localUser("owner@publish.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "게시 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        Long frameVersionId = frameVersionService.publish(owner.getPublicId(), frame(owner, org)).frameVersionId();
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "double-publish", null, null, null, null, null, null, null, null, null, null));
        eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        Long eventId = eventRepository.findByPublicId(created.eventId()).orElseThrow().getId();

        List<Throwable> failures = raceBehind(() -> eventRepository.lockById(eventId),
                () -> eventService.publish(owner.getPublicId(), created.eventId()),
                () -> eventService.publish(owner.getPublicId(), created.eventId()));

        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
        assertThat(failures).singleElement().isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(EventErrorCode.SETTINGS_VERSION_CONFLICT));
    }

    @Test
    @DisplayName("공간 프레임 상한(2) 직전에 두 명이 동시에 만들어도 상한을 넘지 않는다")
    void concurrentWorkspaceFrameCreateRespectsLimit() throws Exception {
        User owner = userRepository.save(User.localUser("owner@frames-limit.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "프레임 상한 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        frame(owner, org);   // 1개 — 남은 자리 1

        List<Throwable> failures = raceBehind(() -> workspaceRepository.lockById(workspaceId),
                () -> frame(owner, org),
                () -> frame(owner, org));

        assertThat(frameRepository.countByWorkspaceId(workspaceId)).isEqualTo(2);
        assertThat(failures).singleElement().isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(FrameErrorCode.WORKSPACE_FRAME_LIMIT));
    }

    @Test
    @DisplayName("개인 공간이 아직 없는 사용자의 첫 개인 프레임 — 개인 공간 지연 생성(REQUIRES_NEW)이 사용자 잠금과 엇갈려 멈추지 않는다")
    void firstPersonalFrameCreatesPersonalWorkspace() throws Exception {
        User user = userRepository.save(User.localUser("first@personal.test", "e", "처음"));
        assertThat(workspaceRepository.findByPersonalOwnerUserId(user.getId())).isEmpty();

        // 사용자 행을 잡은 채 개인 공간을 만들면 그 insert 의 FK 검사가 같은 행을 기다려 잠금 시간 초과(50초)까지 멈춘다
        Long frameId = CompletableFuture.supplyAsync(() -> frameService.createFrame(user.getPublicId(), new FrameCreateRequest("첫 프레임",
                        null, S3Keys.userRoot(user.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                        new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, null)).frameId())
                .get(15, TimeUnit.SECONDS);

        assertThat(frameRepository.findById(frameId)).isPresent();
        assertThat(workspaceRepository.findByPersonalOwnerUserId(user.getId())).isPresent();
    }

    @Test
    @DisplayName("같은 사용자가 workspaceId 생략·개인 공간 id 명시로 동시에 만들어도 교착 없이 둘 다 만들어진다")
    void concurrentPersonalFrameWithAndWithoutWorkspaceId() throws Exception {
        User user = userRepository.save(User.localUser("both@personal.test", "e", "둘다"));
        Workspace personal = workspaceService.personalWorkspaceOf(user.getId());

        // 개인 공간 행을 잡아 둔다 — 예전에는 생략한 쪽이 사용자만 잠근 채 frame insert 의 FK 검사로 이 행을 기다리고,
        // 명시한 쪽은 이 행 → 사용자 순서로 잠그려다 서로를 기다려 교착됐다
        List<Throwable> failures = raceBehind(() -> workspaceRepository.lockById(personal.getId()),
                () -> personalFrame(user, null),
                () -> personalFrame(user, personal.getPublicId()));

        assertThat(failures).isEmpty();   // 교착(Deadlock found)도 다른 실패도 없다
        assertThat(frameRepository.countByWorkspaceId(personal.getId())).isEqualTo(2);
    }

    private void personalFrame(User user, String workspaceId) {
        frameService.createFrame(user.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(user.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, workspaceId));
    }

    private Long frame(User owner, WorkspaceResponse org) {
        return frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
    }

    // lock 으로 행을 잡아 둔 채 두 요청을 출발시키고, 둘 다 기다리는 중에 푼다. 실패한 요청의 예외 목록을 돌려준다
    private List<Throwable> raceBehind(Runnable lock, Runnable a, Runnable b) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            lock.run();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> attempt(a));
        CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(() -> attempt(b));
        Thread.sleep(1000);   // 둘 다 진행하다 잠금에서 기다리는 중
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
