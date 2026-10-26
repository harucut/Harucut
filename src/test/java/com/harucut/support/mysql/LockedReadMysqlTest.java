package com.harucut.support.mysql;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.MembershipService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 「잠금 뒤 세기·번호 매기기」가 REPEATABLE READ 스냅샷에 속지 않는지 — 실제 MySQL.
 * 잠글 행을 먼저 잡아 둔 채 두 요청을 출발시키면, 둘 다 일반 읽기로 스냅샷을 잡은 뒤 잠금에서 기다린다.
 * 풀면 앞쪽이 커밋하고, 뒤쪽은 그 커밋을 잠금 읽기로 봐야 한다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("잠금 읽기 — 마지막 OWNER·발행 번호, 실제 MySQL 동시성")
class LockedReadMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private MembershipService membershipService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameRepository frameRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("두 OWNER 가 동시에 서로를 내려도 OWNER 가 0 명이 되지 않는다")
    void concurrentOwnerDemotionKeepsOneOwner() throws Exception {
        User first = userRepository.save(User.localUser("a@owner.test", "e", "가"));
        User second = userRepository.save(User.localUser("b@owner.test", "e", "나"));
        WorkspaceResponse org = workspaceService.createOrganization(first.getPublicId(), "공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        membershipRepository.save(WorkspaceMembership.member(workspaceId, second.getId(), WorkspaceRole.OWNER));

        List<Throwable> failures = raceBehind(() -> membershipRepository.lockActiveOwnerIds(workspaceId),
                () -> membershipService.changeRole(first.getPublicId(), org.workspaceId(), second.getPublicId(), WorkspaceRole.ADMIN),
                () -> membershipService.changeRole(second.getPublicId(), org.workspaceId(), first.getPublicId(), WorkspaceRole.ADMIN));

        assertThat(membershipRepository.lockActiveOwnerIds(workspaceId)).hasSize(1);
        assertThat(failures).hasSize(1);   // 뒤쪽은 MEMBER-004(마지막 OWNER)
    }

    @Test
    @DisplayName("같은 프레임을 동시에 두 번 발행해도 번호가 겹치지 않는다(1, 2)")
    void concurrentFramePublishGetsDistinctNumbers() throws Exception {
        User owner = userRepository.save(User.localUser("owner@frame.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "프레임 공간");
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();

        List<Throwable> failures = raceBehind(() -> frameRepository.findByIdForUpdate(frameId),
                () -> frameVersionService.publish(owner.getPublicId(), frameId),
                () -> frameVersionService.publish(owner.getPublicId(), frameId));

        assertThat(failures).isEmpty();   // 예전에는 뒤쪽이 같은 번호로 uk_frame_version_frame_no 위반
        assertThat(frameVersionService.list(owner.getPublicId(), frameId)).extracting(FrameVersionResponse::versionNo)
                .containsExactlyInAnyOrder(1, 2);
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
        Thread.sleep(1000);   // 둘 다 스냅샷을 잡고 잠금에서 기다리는 중
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
