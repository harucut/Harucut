package com.harucut.support.mysql;

import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.booth.service.BoothService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.config.RateLimiter;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 부스 연결 코드 — 같은 거래로 겹친 요청이 실패 횟수를 잃거나 소비된 코드를 되살리지 않는지, 실제 MySQL(InnoDB 행 잠금).
 * 다른 트랜잭션이 거래 행을 잡아 둔 채 요청을 출발시키면, 요청은 일반 읽기로 거래를 고른 뒤 그 행을 쓰려다 기다린다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("부스 연결 코드 — 동시 오답·교환, 실제 MySQL")
class BoothPairingMysqlTest {

    private static final String IP = "203.0.113.7";

    // root 로 붙는다 — 기다리는 요청 수를 information_schema.innodb_trx 로 세려면 PROCESS 권한이 있어야 한다
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withUsername("root")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private BoothService boothService;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private EventService eventService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private RateLimiter rateLimiter;

    @BeforeEach
    void allowRequests() {
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
    }

    @Test
    @DisplayName("틀린 코드가 한꺼번에 와도 횟수를 잃지 않는다 — 다섯 번째에 닫히고 잠금 감사는 한 번")
    void concurrentWrongCodesAllCount() throws Exception {
        String code = pairingCode();
        Long pairingId = pairingIdOf(code);

        List<Throwable> failures = behindPairingLock(pairingId, 5, () -> boothService.redeem(wrong(code), IP), () -> { });

        assertThat(failures).extracting(BoothPairingMysqlTest::errorCode).containsExactlyInAnyOrder(
                BoothErrorCode.PAIRING_CODE_INVALID, BoothErrorCode.PAIRING_CODE_INVALID, BoothErrorCode.PAIRING_CODE_INVALID,
                BoothErrorCode.PAIRING_CODE_INVALID, BoothErrorCode.PAIRING_LOCKED);
        assertThat(pairing(pairingId)).containsEntry("failed_attempts", 5).containsEntry("status", "CLOSED");
        assertThat(lockAudits(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("교환이 먼저 커밋되면 뒤늦은 틀린 코드가 거래를 다시 열지 않는다 — 일회용 코드는 한 번만 쓴다")
    void wrongCodeAfterRedeemDoesNotReopen() throws Exception {
        String code = pairingCode();
        Long pairingId = pairingIdOf(code);

        // 잡아 둔 트랜잭션이 맞는 코드의 교환처럼 거래를 소비하고 커밋한다 — 틀린 코드는 그 행 앞에서 기다리는 중이다
        List<Throwable> failures = behindPairingLock(pairingId, 1, () -> boothService.redeem(wrong(code), IP),
                () -> jdbcTemplate.update("update booth_pairing set status = 'REDEEMED' where booth_pairing_id = ?", pairingId));

        assertThat(failures).extracting(BoothPairingMysqlTest::errorCode).containsExactly(BoothErrorCode.PAIRING_CODE_INVALID);
        assertThat(pairing(pairingId)).containsEntry("status", "REDEEMED").containsEntry("failed_attempts", 0);
        assertThatThrownBy(() -> boothService.redeem(code, IP))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(BoothErrorCode.PAIRING_CODE_INVALID);
    }

    // 거래 행을 잡고 whileHeld 를 한 뒤 요청 n 개를 출발시키고, 모두 그 행에서 기다리기 시작하면 커밋해 푼다. 요청마다의 예외를 돌려준다
    private List<Throwable> behindPairingLock(Long pairingId, int requests, Runnable request, Runnable whileHeld) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(requests + 1)) {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
                jdbcTemplate.queryForList("select booth_pairing_id from booth_pairing where booth_pairing_id = ? for update", pairingId);
                whileHeld.run();
                locked.countDown();
                await(release);
            }), pool);
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<CompletableFuture<Throwable>> attempts = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                attempts.add(CompletableFuture.supplyAsync(() -> attempt(request), pool));
            }
            awaitLockWaits(requests);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            List<Throwable> failures = new ArrayList<>();
            for (CompletableFuture<Throwable> attempt : attempts) {
                failures.add(attempt.get(30, TimeUnit.SECONDS));
            }
            return failures;
        }
    }

    // 요청이 모두 행 잠금을 기다리는 중인지 — 시간으로 짐작하지 않고 InnoDB 에 묻는다.
    // innodb_trx 는 0.1초 넘게 아무도 읽지 않았을 때만 다시 채워진다 — 그보다 자주 물으면 처음 본 상태가 계속 나온다
    private void awaitLockWaits(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbcTemplate.queryForObject(
                "select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'", Integer.class) < count) {
            assertThat(System.nanoTime()).as("요청 %d개가 거래 행을 기다리지 않는다", count).isLessThan(deadline);
            Thread.sleep(200);
        }
    }

    private String pairingCode() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User owner = userRepository.save(User.localUser("booth-" + unique + "@harucut.com", "encoded", "운영자"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "부스팀 " + unique);
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(), new EventSettings(
                "부스 " + unique, "booth-" + unique, null, null, null, null, null, null, null, null, null, null, null, null));
        return boothDeviceService.create(owner.getPublicId(), event.eventId(), "부스").pairingCode();
    }

    private Long pairingIdOf(String code) {
        return jdbcTemplate.queryForObject("select booth_pairing_id from booth_pairing where lookup = ? and status = 'OPEN'",
                Long.class, code.substring(0, 4));
    }

    private Map<String, Object> pairing(Long pairingId) {
        return jdbcTemplate.queryForMap("select status, failed_attempts from booth_pairing where booth_pairing_id = ?", pairingId);
    }

    private int lockAudits(String code) {
        Long deviceId = jdbcTemplate.queryForObject("select booth_device_id from booth_pairing where lookup = ?", Long.class,
                code.substring(0, 4));
        return jdbcTemplate.queryForObject("select count(*) from audit_log where action = 'BOOTH_PAIRING_LOCKED' and target_id = ?",
                Integer.class, String.valueOf(deviceId));
    }

    // 비밀 4자의 첫 글자만 바꾼다 — 형식은 맞고 비밀만 틀린 코드
    private static String wrong(String code) {
        return code.substring(0, 5) + (code.charAt(5) == 'A' ? 'B' : 'A') + code.substring(6);
    }

    private static ErrorCode errorCode(Throwable failure) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        return ((BusinessException) failure).getErrorCode();
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
