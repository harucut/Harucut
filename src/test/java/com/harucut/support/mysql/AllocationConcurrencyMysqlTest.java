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
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private com.harucut.entitlement.repository.ContractRepository contractRepository;

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

    @Test
    @DisplayName("리뷰 반영(#39): 같은 배분을 동시에 6→3 두 번 줄여도 부모 합은 3 만 줄어든다")
    void concurrentShrinkUsesCurrentQuota() throws Exception {
        Setup s = setup("shrink", 10);
        String child = contractService.allocate(s.admin().getPublicId(), s.parentId(), new EntitlementDtos.AllocationRequest(s.a().getPublicId(), 6))
                .entitlementId();

        List<Throwable> failures = raceBehind(() -> entitlementRepository.lockById(s.parent()),
                () -> contractService.resize(s.admin().getPublicId(), child, new EntitlementDtos.AllocationResizeRequest(3)),
                () -> contractService.resize(s.admin().getPublicId(), child, new EntitlementDtos.AllocationResizeRequest(3)));

        assertThat(failures).isEmpty();
        assertThat(entitlementRepository.findById(s.parent()).orElseThrow().getAllocatedQuota()).isEqualTo(3);
        assertThat(entitlementRepository.findByPublicId(child).orElseThrow().getComposeQuota()).isEqualTo(3);
    }

    @Test
    @DisplayName("리뷰 반영(#39): 같은 부모·같은 행사를 동시에 두 번 배분하면 하나만 생기고(다른 하나 GEN-002) 부모 합도 한 번만")
    void concurrentDuplicateAllocation() throws Exception {
        Setup s = setup("dup", 10);

        List<Throwable> failures = raceBehind(() -> entitlementRepository.lockById(s.parent()),
                () -> contractService.allocate(s.admin().getPublicId(), s.parentId(), new EntitlementDtos.AllocationRequest(s.a().getPublicId(), 4)),
                () -> contractService.allocate(s.admin().getPublicId(), s.parentId(), new EntitlementDtos.AllocationRequest(s.a().getPublicId(), 4)));

        assertThat(failures).singleElement().isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(com.harucut.common.exception.GlobalErrorCode.INVALID_INPUT_VALUE));
        assertThat(entitlementRepository.findAllByParentEntitlementId(s.parent()))
                .filteredOn(e -> e.getStatus() == com.harucut.entitlement.enums.EntitlementStatus.ACTIVE).hasSize(1);
        assertThat(entitlementRepository.findById(s.parent()).orElseThrow().getAllocatedQuota()).isEqualTo(4);
    }

    @Test
    @DisplayName("리뷰 반영(#39): 같은 배분을 동시에 두 번 회수하면 하나만 되고(ENT-003) 부모 합은 한 번만 돌아온다")
    void concurrentDoubleRevoke() throws Exception {
        Setup s = setup("revoke", 10);
        String child = contractService.allocate(s.admin().getPublicId(), s.parentId(), new EntitlementDtos.AllocationRequest(s.a().getPublicId(), 6))
                .entitlementId();
        contractService.allocate(s.admin().getPublicId(), s.parentId(), new EntitlementDtos.AllocationRequest(s.b().getPublicId(), 2));

        List<Throwable> failures = raceBehind(() -> entitlementRepository.lockById(s.parent()),
                () -> contractService.revoke(s.admin().getPublicId(), child, "취소"),
                () -> contractService.revoke(s.admin().getPublicId(), child, "취소"));

        assertThat(failures).singleElement().isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(EntitlementErrorCode.INVALID_TRANSITION));
        assertThat(entitlementRepository.findById(s.parent()).orElseThrow().getAllocatedQuota()).isEqualTo(2);
    }

    @Test
    @DisplayName("리뷰 반영(#39): 서로 다른 부모의 첫 배분 2건이 동시에 와도 교착 없이 둘 다 성공한다")
    void firstAllocationsOfDifferentParents() throws Exception {
        Setup first = setup("gap-1", 10);
        Setup second = setup("gap-2", 10);

        // 부모 인덱스의 맨 끝 빈 구간을 먼저 잡아 두 배분의 insert 를 멈춰 세운다 — 범위 잠금 읽기로 중복을 검사하면
        // 두 배분이 그 구간에 gap lock 을 함께 잡은 채 서로의 insert 를 기다려 교착된다
        List<Throwable> failures = raceBehind(() -> jdbcTemplate.queryForList(
                        "select entitlement_id from entitlement where parent_entitlement_id = ? for update", Long.MAX_VALUE),
                () -> contractService.allocate(first.admin().getPublicId(), first.parentId(), new EntitlementDtos.AllocationRequest(first.a().getPublicId(), 3)),
                () -> contractService.allocate(second.admin().getPublicId(), second.parentId(), new EntitlementDtos.AllocationRequest(second.a().getPublicId(), 3)));

        assertThat(failures).isEmpty();
        assertThat(entitlementRepository.findAllByParentEntitlementId(first.parent())).hasSize(1);
        assertThat(entitlementRepository.findAllByParentEntitlementId(second.parent())).hasSize(1);
    }

    @Test
    @DisplayName("리뷰 반영(#39): 배분과 계약 종료가 동시에 와도 끝난 계약 아래 유효한 배분이 남지 않는다(계약 → 부모 → 자식)")
    void allocationRacesContractEnd() throws Exception {
        User admin = userRepository.save(User.localUser("admin@end.test", "e", "관리자"));
        User owner = userRepository.save(User.localUser("owner@end.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "계약 공간");
        Event a = event(owner, org, "end-a");
        EntitlementDtos.Contract contract = contractService.create(admin.getPublicId(), new EntitlementDtos.ContractRequest(org.workspaceId(),
                "기업", com.harucut.entitlement.enums.EntitlementScope.WORKSPACE, null, 1000L, "KRW",
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30), null, new EntitlementDtos.Terms(10, null, null, null, Set.of())));
        contractService.changeStatus(admin.getPublicId(), contract.contractId(), com.harucut.entitlement.enums.ContractStatus.ACTIVE);
        Long contractId = contractRepository.findByPublicId(contract.contractId()).orElseThrow().getId();
        String parentId = entitlementRepository.findAllByContractId(contractId).getFirst().getPublicId();

        List<Throwable> failures = raceBehind(() -> contractRepository.lockStatus(contractId),
                () -> contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(a.getPublicId(), 3)),
                () -> contractService.changeStatus(admin.getPublicId(), contract.contractId(), com.harucut.entitlement.enums.ContractStatus.ENDED));

        assertThat(failures).allSatisfy(f -> assertThat(f).isInstanceOf(BusinessException.class));   // 교착(500)이 아니다
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        assertThat(entitlementRepository.findAllByContractId(contractId))
                .noneMatch(e -> e.getStatus() == com.harucut.entitlement.enums.EntitlementStatus.ACTIVE && e.getValidTo().isAfter(now));
    }

    private record Setup(User admin, String parentId, Long parent, Event a, Event b) {
    }

    private Setup setup(String prefix, int quota) {
        User admin = userRepository.save(User.localUser("admin@" + prefix + ".test", "e", "관리자"));
        User owner = userRepository.save(User.localUser("owner@" + prefix + ".test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), prefix);
        Event a = event(owner, org, prefix + "-a");
        Event b = event(owner, org, prefix + "-b");
        String parentId = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(org.workspaceId(), null,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30),
                new EntitlementDtos.Terms(quota, null, null, null, Set.of()), "기업 총량")).entitlementId();
        return new Setup(admin, parentId, entitlementRepository.findByPublicId(parentId).orElseThrow().getId(), a, b);
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
