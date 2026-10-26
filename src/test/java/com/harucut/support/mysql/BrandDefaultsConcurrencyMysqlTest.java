package com.harucut.support.mysql;

import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 공간·프로젝트 기본값은 읽고 머지해 다시 쓴다 — 두 관리자가 서로 다른 키를 동시에 PATCH 해도 둘 다 남아야 한다(실제 MySQL).
 * 공간 행을 잡아 둔 채 두 PATCH 를 출발시키고 둘 다 기다리는 중에 푼다. 잠그지 않으면 같은 JSON 을 읽어 나중 쓰기가 앞 변경을 지운다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("브랜드 기본값 동시 PATCH — 실제 MySQL")
class BrandDefaultsConcurrencyMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private ProjectService projectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("공간 기본값: 서로 다른 키를 동시에 바꿔도 두 키가 모두 남는다")
    void workspaceDefaultsKeepBothKeys() throws Exception {
        User owner = userRepository.save(User.localUser("ws@defaults.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "기본값 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();

        List<Throwable> failures = raceBehind(() -> workspaceRepository.lockById(workspaceId),
                () -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(), objectMapper.readTree("{\"timezone\":\"Asia/Tokyo\"}")),
                () -> projectService.patchWorkspaceDefaults(owner.getPublicId(), org.workspaceId(), objectMapper.readTree("{\"resultRetentionDays\":60}")));

        assertThat(failures).isEmpty();
        var defaults = projectService.workspaceDefaults(owner.getPublicId(), org.workspaceId()).brandDefaults();
        assertThat(defaults.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(defaults.resultRetentionDays()).isEqualTo(60);
    }

    @Test
    @DisplayName("프로젝트 기본값: 서로 다른 키를 동시에 바꿔도 두 키가 모두 남는다(잠금 순서 공간 → 프로젝트)")
    void projectDefaultsKeepBothKeys() throws Exception {
        User owner = userRepository.save(User.localUser("pj@defaults.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "프로젝트 공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));

        List<Throwable> failures = raceBehind(() -> workspaceRepository.lockById(workspaceId),
                () -> projectService.patch(owner.getPublicId(), project.projectId(), objectMapper.readTree("{\"brandDefaults\":{\"boothEnabled\":true}}")),
                () -> projectService.patch(owner.getPublicId(), project.projectId(), objectMapper.readTree("{\"brandDefaults\":{\"resultRetentionDays\":45}}")));

        assertThat(failures).isEmpty();
        var defaults = projectService.list(owner.getPublicId(), org.workspaceId()).getFirst().brandDefaults();
        assertThat(defaults.boothEnabled()).isTrue();
        assertThat(defaults.resultRetentionDays()).isEqualTo(45);
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
        Thread.sleep(1000);   // 둘 다 읽고(예전 코드) 또는 잠금에서(지금 코드) 기다리는 중
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
