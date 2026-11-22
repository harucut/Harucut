package com.harucut.support.mysql;

import com.harucut.asset.batch.AssetOrphanCleaner;
import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

/*
 * 정리 배치의 쿼리(NOT EXISTS 조건부 삭제·배경 LIKE 네이티브)를 실제 MySQL 에서 돌린다 — H2 와 방언이 다를 수 있는 곳.
 * 테스트 트랜잭션 없이 돌려 자산마다의 트랜잭션이 실제로 커밋되는지(행이 사라지는지)를 본다.
 * S3Deleter 는 가짜라 "삭제 예약에 넘긴 key" 까지만 확인한다 — 커밋 뒤 실행은 S3Deleter 자신의 계약이다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("미참조 자산 정리 — 실제 MySQL")
class AssetOrphanCleanupMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private AssetOrphanCleaner cleaner;
    @Autowired private AssetRepository assetRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    @Test
    @DisplayName("오래된 미참조 자산은 지워지고 새 자산은 남는다")
    void deletesOnMysql() {
        Asset old = assetRepository.save(Asset.pendingUpload(Asset.newPublicId(), null, AssetPurpose.FRAME_ASSET,
                "uploads/system/old.png", "image/png", 100, null));
        Asset fresh = assetRepository.save(Asset.pendingUpload(Asset.newPublicId(), null, AssetPurpose.FRAME_ASSET,
                "uploads/system/fresh.png", "image/png", 100, null));
        jdbcTemplate.update("update asset set created_at = ? where asset_id = ?", LocalDateTime.now().minusDays(8), old.getId());

        assertThat(cleaner.run()).isEqualTo(1);

        assertThat(assetRepository.findById(old.getId())).isEmpty();
        assertThat(assetRepository.findById(fresh.getId())).isPresent();
        then(s3Deleter).should().deleteAfterCommit(List.of("uploads/system/old.png"));
    }

    @Test
    @DisplayName("끝나지 않은 합성의 스냅샷이 쓰는 자산은 지우지 않는다 — 합성이 끝나면 다음 실행이 지운다")
    void keepsAssetsUsedByPendingComposeOnMysql() {
        User user = userRepository.save(User.localUser("pending@orphan.test", "e", "p"));
        Asset layer = assetRepository.save(Asset.pendingUpload(Asset.newPublicId(), null, AssetPurpose.FRAME_ASSET,
                "uploads/system/pending-layer.png", "image/png", 100, null));
        // 접수한 합성은 레이어 key 를 spec_json 에 스냅샷만 한다 — 자산 참조(FK)는 없다. 그 뒤 프레임이 이 자산을 뺐다
        ComposeJob job = composeJobRepository.save(ComposeJob.create(user, 1L, "idem-pending-layer",
                List.of("a", "b", "c", "d"), specWithLayer(layer.getS3Key())));
        jdbcTemplate.update("update asset set created_at = ? where asset_id = ?", LocalDateTime.now().minusDays(8), layer.getId());

        cleaner.run();
        assertThat(assetRepository.findById(layer.getId())).isPresent();
        // 후보 검사 뒤에 접수된 합성도 지우기 직전의 조건(NOT EXISTS … spec_json LIKE)이 막는다 — MySQL 1093 없이
        Integer deleted = new TransactionTemplate(transactionManager).execute(status ->
                assetRepository.deleteIfUnreferenced(layer.getId(), layer.getS3Key()));
        assertThat(deleted).isZero();

        jdbcTemplate.update("update compose_job set status = 'DONE' where compose_job_id = ?", job.getId());
        cleaner.run();
        assertThat(assetRepository.findById(layer.getId())).isEmpty();
    }

    private static ComposeSpec specWithLayer(String key) {
        return new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFFFFF"), FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false), List.of(new ComposeSpec.Layer(key, 0, 0, 10, 10, 1, 0, 1, 1)));
    }
}
