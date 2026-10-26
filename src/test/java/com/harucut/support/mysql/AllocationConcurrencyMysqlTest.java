package com.harucut.support.mysql;

import com.harucut.common.exception.BusinessException;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.entitlement.service.ContractService;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * ADR-0009 §5 검수(O04) — 기업 잔량 1·두 행사 각각 배분 잔량 있음에서 동시 접수 2건 → 정확히 1건만 성공(실제 MySQL).
 * 부모 행을 잡아 둔 채 두 예약을 출발시켜 둘 다 부모 UPDATE 에서 기다리게 하고 푼다. 진 쪽은 교착이 아니라 ENT-002 여야 한다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("기업 총량·행사 배분 동시 접수 — 실제 MySQL")
class AllocationConcurrencyMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private EntitlementService entitlementService;
    @Autowired private ContractService contractService;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("기업 잔량 1에서 서로 다른 배분 행사의 동시 접수 2건 → 정확히 1건 성공, 진 쪽은 ENT-002")
    void onlyOneSucceedsWhenParentHasOneLeft() throws Exception {
        User admin = userRepository.save(User.localUser("admin@alloc.test", "e", "관리자"));
        User owner = userRepository.save(User.localUser("owner@alloc.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "기업");
        Event a = event(owner, org, "alloc-a");
        Event b = event(owner, org, "alloc-b");
        Event plain = event(owner, org, "alloc-plain");
        String parentId = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(org.workspaceId(), null,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30),
                new EntitlementDtos.Terms(2, null, null, null, Set.of()), "기업 총량")).entitlementId();
        contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(a.getPublicId(), 1));
        contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(b.getPublicId(), 1));
        entitlementService.reserve(job(plain, "plain-1"), plain);   // 기업 잔량 1
        Long parent = entitlementRepository.findByPublicId(parentId).orElseThrow().getId();
        Long jobA = job(a, "a-1");
        Long jobB = job(b, "b-1");

        List<Throwable> failures = raceBehind(() -> entitlementRepository.lockById(parent),
                () -> entitlementService.reserve(jobA, a),
                () -> entitlementService.reserve(jobB, b));

        assertThat(failures).singleElement().isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(EntitlementErrorCode.QUOTA_REACHED));
        var parentNow = entitlementRepository.findById(parent).orElseThrow();
        assertThat(parentNow.getUsed() + parentNow.getReserved()).isEqualTo(2);   // 넘지 않았다
    }

    private Event event(User owner, WorkspaceResponse org, String slug) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings(slug, slug, null, null, null, null, null, null, null, null, null, null, null, null));
        return eventRepository.findByPublicId(created.eventId()).orElseThrow();
    }

    private Long job(Event event, String key) {
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        return composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, null)).getId();
    }

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
        Thread.sleep(1000);   // 둘 다 부모 UPDATE 에서 기다리는 중
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
