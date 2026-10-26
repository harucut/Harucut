package com.harucut.support.mysql;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.export.entity.ExportJob;
import com.harucut.export.enums.ExportStatus;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.export.service.ExportWorker;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.storage.batch.S3DeletionRetrier;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

/*
 * #41 Codex — 종료 때 내려놓은 묶음을 다시 만들 때, 앞 시도의 업로드 책임(삭제 예약)이 새 업로드 도중 집혀도 새 ZIP 은 남는다.
 * 재시도 배치는 행을 잠그지 않고 읽어 지우므로 같은 key 를 다시 쓰면 막을 수 없다 — 다시 만들 때는 새 key 로 올린다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql",
        // 이 테스트가 작업자를 직접 돌린다 — 앱의 주기 재투입이 끼어들지 않게
        "event.export.recover-interval=1h"
})
@ActiveProfiles("test")
@DisplayName("묶음 내려놓기 뒤 재시작 — 앞 시도의 삭제 예약이 새 ZIP 을 지우지 않는다(실제 MySQL)")
class ExportHandBackMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private ExportJobRepository exportJobRepository;
    @Autowired private ExportJobItemRepository itemRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserRepository userRepository;
    @Autowired private S3Deleter s3Deleter;
    @Autowired private S3DeletionRepository deletionRepository;
    @Autowired private S3DeletionRetrier retrier;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 삭제 시도(S3DeletionAttempt)가 쓰는 저장소
    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("1차 시도가 종료로 내려놓음 → 옛 key 삭제가 실패해 예약이 남음 → 2차 업로드 도중 재시도 배치가 그 예약을 집음 → 새 ZIP 은 DONE, 지워진 것은 옛 key 뿐")
    void oldReservationNeverTouchesRetriedUpload() {
        User owner = userRepository.save(User.localUser("handback@harucut.com", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "내려놓기");
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "handback", null, null, null, null, null, null, null, null, null, null, null, null));
        Event event = eventRepository.findByPublicId(created.eventId()).orElseThrow();
        ExportJob saved = exportJobRepository.save(ExportJob.pending(event.getWorkspaceId(), event.getId(), event.getPublicId(), owner.getId(), 1));
        String oldKey = saved.getS3Key();
        willThrow(new IllegalStateException("S3 장애")).given(fileStorageService).delete(oldKey);   // 옛 key 의 첫 삭제는 실패 — 예약이 남는다

        // 1차: 업로드를 열려는 순간 종료가 시작된다
        FileStorageService firstStorage = mock(FileStorageService.class);
        ExportWorker first = worker(firstStorage);
        given(firstStorage.openMultipartUpload(anyString(), anyString())).willAnswer(i -> {
            first.shutdown();
            throw new IllegalStateException("종료 인터럽트");
        });
        first.run(saved.getId());

        ExportJob handedBack = exportJobRepository.findById(saved.getId()).orElseThrow();
        assertThat(handedBack.getStatus()).isEqualTo(ExportStatus.PENDING);
        String newKey = handedBack.getS3Key();
        assertThat(deletionRepository.findAll()).singleElement().satisfies(row -> assertThat(row.getS3Key()).isEqualTo(oldKey));
        // 오래 기다려 그 예약의 차례가 이미 지났다
        jdbcTemplate.update("update s3_deletion set next_attempt_at = ? where key_hash = ?",
                LocalDateTime.now().minusMinutes(1), OpaqueTokens.hash(oldKey));

        // 2차: 업로드를 연 직후 재시도 배치가 돈다
        reset(fileStorageService);   // S3 가 되살아났다
        FileStorageService secondStorage = mock(FileStorageService.class);
        S3Client s3Client = mock(S3Client.class);
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .willReturn(UploadPartResponse.builder().eTag("e").build());
        given(secondStorage.openMultipartUpload(anyString(), anyString())).willAnswer(i -> {
            retrier.retryDue();
            return new S3MultipartOutputStream(s3Client, "bucket", i.getArgument(0), "application/zip", 1024);
        });
        worker(secondStorage).run(saved.getId());

        ExportJob done = exportJobRepository.findById(saved.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ExportStatus.DONE);
        assertThat(done.getS3Key()).isEqualTo(newKey);
        then(fileStorageService).should(never()).delete(newKey);   // 받을 ZIP 은 지워지지 않았다
        then(fileStorageService).should().delete(oldKey);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(deletionRepository.findAll()).isEmpty();   // 옛 예약은 지워서 끝났고, 새 key 의 책임은 DONE 과 함께 풀렸다
    }

    private ExportWorker worker(FileStorageService storage) {
        return new ExportWorker(exportJobRepository, itemRepository, composeJobRepository, eventRepository, storage, s3Deleter,
                transactionManager, Clock.systemDefaultZone(), Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(5));
    }
}
