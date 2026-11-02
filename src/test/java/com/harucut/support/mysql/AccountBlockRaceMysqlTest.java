package com.harucut.support.mysql;

import com.harucut.admin.service.AdminUserService;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.service.RefreshTokenRepository;
import com.harucut.auth.service.UserExitService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 관리자 두 명이 서로를 동시에 정지 — 행 잠금으로 줄을 서서 뒤에 온 쪽은 자기가 정지된 것을 보고 403 이다.
 * 교착도, 관리자 0명도 없어야 한다(「마지막 ADMIN 은 정지하지 못한다」를 이 판정이 지킨다).
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("계정 정지 경쟁 — 실제 MySQL")
class AccountBlockRaceMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private AdminUserService adminUserService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserExitService userExitService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("서로를 동시에 정지해도 교착 없이 한 명만 정지되고 한 명은 ADMIN 으로 남는다")
    void adminsBlockEachOther() throws Exception {
        for (int round = 0; round < 20; round++) {
            User a = admin();
            User b = admin();
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<String> first = attempt(start, a, b);
            CompletableFuture<String> second = attempt(start, b, a);
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("BLOCKED", GlobalErrorCode.FORBIDDEN.getCode());
            assertThat(List.of(status(a), status(b))).containsExactlyInAnyOrder(UserStatus.ACTIVE, UserStatus.BLOCKED);
        }
    }

    @Test
    @DisplayName("정지와 동시에 커밋되는 프로필 저장이 정지를 되돌리지 않는다 — 바뀐 열만 쓴다")
    void profileSaveDoesNotUndoBlock() {
        User admin = admin();
        User member = member();

        transactions().executeWithoutResult(status -> {
            User stale = userRepository.findByPublicId(member.getPublicId()).orElseThrow();
            stale.changeUsername("새 이름");
            // 이 트랜잭션이 읽은 뒤, 커밋하기 전에 다른 트랜잭션에서 정지가 커밋된다
            CompletableFuture.runAsync(() -> adminUserService.block(admin.getPublicId(), member.getPublicId(), "신고")).join();
        });

        User after = userRepository.findByPublicId(member.getPublicId()).orElseThrow();
        assertThat(after.getUserStatus()).isEqualTo(UserStatus.BLOCKED);
        assertThat(after.getUsername()).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("탈퇴 요청과 정지가 동시에 — 어느 쪽이 먼저든 한쪽만 된다. 탈퇴 요청이 정지를 덮지 않는다")
    void exitRequestAndBlockRace() throws Exception {
        for (int round = 0; round < 20; round++) {
            User admin = admin();
            User member = member();
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<String> block = attempt(start, admin, member);
            CompletableFuture<String> exit = CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    userExitService.requestExit(member.getPublicId());
                    return "EXIT";
                } catch (BusinessException e) {
                    return e.getErrorCode().getCode();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            start.countDown();

            String blockResult = block.get();
            String exitResult = exit.get();
            if ("BLOCKED".equals(blockResult)) {
                assertThat(exitResult).isEqualTo(AuthErrorCode.ACCOUNT_BLOCKED.getCode());
                assertThat(status(member)).isEqualTo(UserStatus.BLOCKED);
            } else {
                assertThat(blockResult).isEqualTo(AuthErrorCode.ACCOUNT_PENDING_DELETION.getCode());
                assertThat(exitResult).isEqualTo("EXIT");
                assertThat(status(member)).isEqualTo(UserStatus.DELETED_REQUESTED);
            }
        }
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private User member() {
        return userRepository.save(User.localUser(UUID.randomUUID() + "@harucut.com", "e", "회원"));
    }

    private CompletableFuture<String> attempt(CountDownLatch start, User actor, User target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return adminUserService.block(actor.getPublicId(), target.getPublicId(), "경쟁").userStatus().name();
            } catch (BusinessException e) {
                return e.getErrorCode().getCode();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private User admin() {
        User user = userRepository.save(User.localUser(UUID.randomUUID() + "@harucut.com", "e", "관리자"));
        jdbcTemplate.update("update users set user_role = 'ROLE_ADMIN' where user_id = ?", user.getId());
        return user;
    }

    private UserStatus status(User user) {
        return userRepository.findByPublicId(user.getPublicId()).orElseThrow().getUserStatus();
    }
}
