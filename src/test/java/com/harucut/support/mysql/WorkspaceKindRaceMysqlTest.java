package com.harucut.support.mysql;

import com.harucut.admin.service.AdminWorkspaceService;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceKind;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 공간 종류 변경은 공간 행 전체를 다시 쓴다 — 브랜드 기본값 저장과 동시에 커밋되면 잠그지 않은 읽기가 옛 기본값을 되쓴다(#51 리뷰).
 * 공간 행을 잡아 둔 채 둘을 출발시키고 기다리는 중에 푼다. 둘 다 잠금 읽기로 시작하므로 줄을 서고, 결과에 두 변경이 모두 남는다
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("공간 종류 변경과 브랜드 기본값 저장의 경합 — 실제 MySQL")
class WorkspaceKindRaceMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private AdminWorkspaceService adminWorkspaceService;
    @Autowired private ProjectService projectService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("종류 변경과 기본값 저장이 동시에 커밋돼도 둘 다 남는다 — 종류 변경이 낡은 기본값을 되쓰지 않는다")
    void kindChangeKeepsConcurrentDefaults() throws Exception {
        User owner = userRepository.save(User.localUser("kind@race.test", "e", "오너"));
        User admin = userRepository.save(User.localUser("admin@race.test", "e", "관리자"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "경합 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();

        List<Throwable> failures = raceBehind(() -> workspaceRepository.lockById(workspaceId),
                () -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(), objectMapper.readTree("{\"timezone\":\"Asia/Tokyo\"}")),
                () -> adminWorkspaceService.changeKind(admin.getPublicId(), org.workspaceId(), WorkspaceKind.ENTERPRISE, "계약"));

        assertThat(failures).isEmpty();
        Workspace after = workspaceRepository.findById(workspaceId).orElseThrow();
        assertThat(after.getKind()).isEqualTo(WorkspaceKind.ENTERPRISE);
        assertThat(projectService.workspaceDefaults(owner.getPublicId(), org.workspaceId()).brandDefaults().timezone())
                .isEqualTo("Asia/Tokyo");
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
        Thread.sleep(1000);   // 둘 다 잠금에서 기다리는 중
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
