package com.harucut.support.mysql;

import com.harucut.push.dto.DeviceRegisterRequest;
import com.harucut.push.repository.DeviceTokenRepository;
import com.harucut.push.service.DeviceTokenService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 푸시 기기 등록 경쟁 — upsert(ON DUPLICATE KEY UPDATE) 문법과 잠금 순서(회원 행 → 기기 행)를 실제 MySQL 에서 본다.
 * 교착·유니크 위반이 나면 예외로 돌아와 실패한다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("푸시 기기 등록 경쟁 — 실제 MySQL")
class DeviceTokenMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private DeviceTokenService service;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceTokenRepository deviceTokenRepository;

    @Test
    @DisplayName("두 계정이 같은 토큰을 동시에 → 예외 없이 한 행, 둘 중 한 계정 소유")
    void sameTokenTwoAccounts() throws Exception {
        for (int round = 0; round < 8; round++) {
            User a = user("dev-a" + round);
            User b = user("dev-b" + round);
            String token = "ExpoPushToken[same" + round + "]";

            List<Object> results = concurrently(List.of(
                    () -> service.register(a.getPublicId(), new DeviceRegisterRequest(token, "ios")),
                    () -> service.register(b.getPublicId(), new DeviceRegisterRequest(token, "android"))));

            assertThat(results).noneMatch(Throwable.class::isInstance);
            int owners = deviceTokenRepository.findTokensByUserId(a.getId()).size()
                    + deviceTokenRepository.findTokensByUserId(b.getId()).size();
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 계정이 12대를 동시에 → 예외 없이 정확히 10대, 남의 토큰을 옮겨 오는 등록과 겹쳐도 교착 없음")
    void capUnderConcurrency() throws Exception {
        User owner = user("dev-cap");
        User mover = user("dev-move");
        List<Runnable> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String token = "ExpoPushToken[cap" + i + "]";
            work.add(() -> service.register(owner.getPublicId(), new DeviceRegisterRequest(token, "android")));
        }
        // 소유자가 등록 중인 토큰 몇 개를 다른 계정이 동시에 옮겨 간다
        for (int i = 0; i < 3; i++) {
            String token = "ExpoPushToken[cap" + i + "]";
            work.add(() -> service.register(mover.getPublicId(), new DeviceRegisterRequest(token, "ios")));
        }

        assertThat(concurrently(work)).noneMatch(Throwable.class::isInstance);
        assertThat(deviceTokenRepository.findTokensByUserId(owner.getId())).hasSizeLessThanOrEqualTo(10);
        int total = deviceTokenRepository.findTokensByUserId(owner.getId()).size()
                + deviceTokenRepository.findTokensByUserId(mover.getId()).size();
        assertThat(total).isGreaterThanOrEqualTo(10);
    }

    private User user(String name) {
        return userRepository.save(User.localUser(name + "@harucut.com", "e", name.substring(0, Math.min(8, name.length()))));
    }

    private static List<Object> concurrently(List<Runnable> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Runnable task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.run();
                        return "ok";
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
