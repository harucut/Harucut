package com.harucut.support.mysql;

import com.harucut.media.entity.UserMedia;
import com.harucut.media.handler.UserMediaDeletionHandler;
import com.harucut.media.repository.MediaShareRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.media.service.MediaShareService;
import com.harucut.media.service.UserMediaService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 개인 사진 공유 링크(V39) — H2 로는 볼 수 없는 것만 실제 MySQL 에서 본다.
 * (1) 동시에 두 번 눌렀을 때: 늦은 쪽의 앞선 읽기 스냅샷(REPEATABLE READ)에는 먼저 들어간 행이 없다 — 잠금 읽기로 받아야 같은 토큰이다.
 * (2) 토큰 컬럼은 ascii_bin — 서버 기본(utf8mb4_unicode_ci)이면 대소문자만 바꾼 토큰도 열린다.
 * (3) FK — H2 스키마에는 공유 행의 FK 가 없다(스칼라 id). 사진 삭제·탈퇴가 공유 행을 먼저 지워야 한다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("사진 공유 링크 — 실제 MySQL")
class MediaShareMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private MediaShareService mediaShareService;
    @Autowired private UserMediaService userMediaService;
    @Autowired private UserMediaDeletionHandler userMediaDeletionHandler;
    @Autowired private UserRepository userRepository;
    @Autowired private UserMediaRepository userMediaRepository;
    @Autowired private MediaShareRepository mediaShareRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;

    private TransactionTemplate transaction;
    private User owner;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        owner = transaction.execute(status ->
                userRepository.save(User.localUser("share-" + UUID.randomUUID() + "@harucut.com", "e", "주인")));
    }

    @Test
    @DisplayName("동시에 두 번 눌러도 유니크 위반 없이 같은 토큰 — 늦은 쪽은 앞쪽 커밋을 기다렸다가 그 토큰을 받는다")
    void concurrentLinksShareOneToken() throws Exception {
        Long mediaId = media();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            String token = mediaShareService.link(owner.getPublicId(), mediaId).token();
            firstInserted.countDown();
            await(releaseFirst);
            return token;
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
        // 앞쪽이 아직 커밋 전 — 늦은 쪽은 media_id 유니크 키 잠금에서 기다린다
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() ->
                transaction.execute(status -> mediaShareService.link(owner.getPublicId(), mediaId).token()));
        Thread.sleep(500);
        assertThat(second).isNotDone();
        releaseFirst.countDown();

        String token = first.get(20, TimeUnit.SECONDS);
        assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(token);   // 예외(롤백 전용) 없이, 같은 링크
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_share WHERE media_id = ?", Integer.class, mediaId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("토큰은 대소문자를 가린다 — 대소문자만 바꾼 토큰으로는 찾지 못한다")
    void tokenIsCaseSensitive() {
        String token = link(media());

        assertThat(mediaShareRepository.findByToken(token)).isPresent();
        assertThat(mediaShareRepository.findByToken(swapCase(token))).isEmpty();
    }

    @Test
    @DisplayName("공유 행은 사진을 FK 로 잡는다 — 사진 삭제와 탈퇴 처리는 공유 행을 먼저 지워 FK 에 걸리지 않는다")
    void deletionsClearLinksFirst() {
        Long deleted = media();
        link(deleted);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM user_media WHERE media_id = ?", deleted))
                .isInstanceOf(DataIntegrityViolationException.class);

        userMediaService.deleteMedia(owner.getPublicId(), deleted);
        assertThat(count("SELECT COUNT(*) FROM user_media WHERE media_id = ?", deleted)).isZero();
        assertThat(count("SELECT COUNT(*) FROM media_share WHERE media_id = ?", deleted)).isZero();

        Long kept = media();
        Long other = media();
        link(kept);
        link(other);
        userMediaDeletionHandler.handleUserDeletion(owner.getId());
        assertThat(count("SELECT COUNT(*) FROM user_media WHERE user_id = ?", owner.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_share WHERE media_id IN (?, ?)", Integer.class, kept, other))
                .isZero();
    }

    private Long media() {
        String key = S3Keys.userRoot(owner.getPublicId()) + "fourcuts/job-" + UUID.randomUUID() + ".jpg";
        return transaction.execute(status -> userMediaRepository.save(UserMedia.of(owner, key, "사진.jpg")).getId());
    }

    private String link(Long mediaId) {
        return mediaShareService.link(owner.getPublicId(), mediaId).token();
    }

    private int count(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private static String swapCase(String token) {
        StringBuilder swapped = new StringBuilder();
        token.chars().forEach(c -> swapped.append((char) (Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c))));
        return swapped.toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
