package com.harucut.export;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.entity.ExportJob;
import com.harucut.export.enums.ExportStatus;
import com.harucut.export.exception.ExportErrorCode;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.export.service.ExportService;
import com.harucut.export.service.ExportWorker;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/*
 * P2B-3 — 묶음 다운로드: 접수 → 워커가 결과를 ZIP 으로 흘려 multipart 로 올림 → 수령(권한·기한·삭제 재검사)을 실제 JPA 로 본다.
 * S3 는 가짜 클라이언트로 multipart 파트 바이트를 모아 실제 ZIP 을 풀어 확인한다. 워커는 커밋 뒤 비동기 대신 직접 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("묶음 다운로드 — 통합")
class ExportIntegrationTest {

    @Autowired private ExportService exportService;
    @Autowired private ExportWorker exportWorker;
    @Autowired private ExportJobRepository exportJobRepository;
    @Autowired private PhotoService photoService;
    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private EventRepository eventRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
    private S3Client s3Client;
    private User owner;
    private Event event;
    private ComposeJob first;
    private ComposeJob second;
    private ComposeJob failed;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).willAnswer(invocation -> {
            uploaded.write(((RequestBody) invocation.getArgument(1)).contentStreamProvider().newStream().readAllBytes());
            return UploadPartResponse.builder().eTag("e").build();
        });
        given(fileStorageService.openMultipartUpload(anyString(), anyString()))
                .willAnswer(invocation -> new S3MultipartOutputStream(s3Client, "bucket", invocation.getArgument(0), "application/zip", 1024));
        given(fileStorageService.openStream(anyString()))
                .willAnswer(invocation -> new ByteArrayInputStream(("photo:" + invocation.getArgument(0)).getBytes()));
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/zip");

        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        // 묶음 다운로드는 계약 기능 BULK_DOWNLOAD 가 필요하다(P2B-5)
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "export-event", null, null, null, null, null, null, null, null, null, null, null, null));
        event = eventRepository.findByPublicId(created.eventId()).orElseThrow();
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        first = done(participant, "k1", "uploads/events/e/results/1.jpg");
        second = done(participant, "k2", "uploads/events/e/results/2.jpg");
        failed = job(participant, "k3");
        composeJobRepository.markFailed(failed.getId(), "x", ComposeStatus.PENDING, ComposeStatus.FAILED);
        // 상태 전이는 벌크 UPDATE 라 영속성 컨텍스트의 작업은 아직 PENDING 이다 — 운영에서는 전이가 다른 트랜잭션이다
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("여러 인스턴스 정리 쿼리: 신호가 끊긴 RUNNING 만 찾고, 조건부 실패는 그사이 신호가 오면 0, 내려놓기는 RUNNING→PENDING")
    void recoveryQueries() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = now.minusMinutes(5);
        Long fresh = exportJobRepository.save(ExportJob.pending(event.getWorkspaceId(), event.getId(), event.getPublicId(), owner.getId(), 1)).getId();
        Long stale = exportJobRepository.save(ExportJob.pending(event.getWorkspaceId(), event.getId(), event.getPublicId(), owner.getId(), 1)).getId();
        Long lost = exportJobRepository.save(ExportJob.pending(event.getWorkspaceId(), event.getId(), event.getPublicId(), owner.getId(), 1)).getId();
        exportJobRepository.claim(fresh, now.minusMinutes(1));
        exportJobRepository.claim(stale, now.minusMinutes(10));
        exportJobRepository.claim(lost, now.minusMinutes(10));

        assertThat(exportJobRepository.findAllByStatusAndUpdatedAtBefore(ExportStatus.RUNNING, before))
                .extracting(ExportJob::getId).containsExactlyInAnyOrder(stale, lost);
        assertThat(exportJobRepository.touch(stale, now)).isEqualTo(1);   // 읽은 뒤 그 작업자가 신호를 보냈다
        assertThat(exportJobRepository.failStale(stale, before, "WORKER_LOST", now)).isZero();
        assertThat(exportJobRepository.failStale(fresh, before, "WORKER_LOST", now)).isZero();
        assertThat(exportJobRepository.failStale(lost, before, "WORKER_LOST", now)).isEqualTo(1);
        assertThat(exportJobRepository.requeue(fresh, "uploads/events/e/exports/retry.zip", now)).isEqualTo(1);
        assertThat(exportJobRepository.requeue(lost, "uploads/events/e/exports/lost.zip", now)).isZero();   // 이미 FAILED

        assertThat(exportJobRepository.findById(fresh).orElseThrow()).satisfies(job -> {
            assertThat(job.getStatus()).isEqualTo(ExportStatus.PENDING);
            assertThat(job.getS3Key()).isEqualTo("uploads/events/e/exports/retry.zip");   // 내려놓으면 새 key
        });
        assertThat(exportJobRepository.findById(stale).orElseThrow().getStatus()).isEqualTo(ExportStatus.RUNNING);
        assertThat(exportJobRepository.findById(lost).orElseThrow().getStatus()).isEqualTo(ExportStatus.FAILED);
    }

    @Test
    @DisplayName("고른 순서대로 완성 사진만 ZIP 에 담고, 받으면 「{행사명}-{YYYYMMDD}.zip」 5분 URL")
    void selectedPhotosZipped() throws IOException {
        ExportDtos.Export export = exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(second.getId(), failed.getId(), first.getId()), null));
        assertThat(export.status()).isEqualTo(ExportStatus.PENDING);
        assertThat(export.photoCount()).isEqualTo(2);   // 실패 사진은 뺐다
        assertCode(() -> download(export), ExportErrorCode.NOT_READY);

        exportWorker.run(id(export));

        ExportDtos.Export done = exportService.get(owner.getPublicId(), event.getPublicId(), export.exportId());
        assertThat(done.status()).isEqualTo(ExportStatus.DONE);
        assertThat(done.sizeBytes()).isEqualTo(uploaded.size());
        assertThat(done.expiresAt()).isNotNull();
        assertThat(unzip(uploaded.toByteArray())).containsExactly(
                Map.entry("0001-" + second.getId() + ".jpg", "photo:uploads/events/e/results/2.jpg"),
                Map.entry("0002-" + first.getId() + ".jpg", "photo:uploads/events/e/results/1.jpg"));

        assertThat(download(export).url()).isEqualTo("https://s3/zip");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        then(fileStorageService).should().generatePresignedDownloadUrl(anyString(), eq("민지 ♥ 준호-" + today + ".zip"), any());
    }

    @Test
    @DisplayName("고른 것이 비면(빈 목록 포함) EXPORT-003, 아무것도 안 보내면 완성 사진 전부")
    void selection() {
        assertCode(() -> exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(failed.getId()), null)), ExportErrorCode.INVALID_SELECTION);
        // 명시적 빈 목록은 빈 선택이다 — 필터·전체로 떨어지지 않는다
        assertCode(() -> exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(), null)), ExportErrorCode.INVALID_SELECTION);
        assertThat(exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(null, null)).photoCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("담긴 사진을 지우면 묶음이 무효(EXPIRED)가 되고 객체 삭제 예약, 받으면 410 EXPORT-002")
    void deletedPhotoInvalidates() {
        ExportDtos.Export export = exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(null, null));
        exportWorker.run(id(export));
        String key = exportJobRepository.findById(id(export)).orElseThrow().getS3Key();

        photoService.delete(owner.getPublicId(), event.getPublicId(), first.getId());

        assertThat(exportService.get(owner.getPublicId(), event.getPublicId(), export.exportId()).status())
                .isEqualTo(ExportStatus.EXPIRED);
        then(s3Deleter).should().deleteAfterCommit(List.of(key));
        assertCode(() -> download(export), ExportErrorCode.GONE);
    }

    @Test
    @DisplayName("읽기가 실패하면 multipart 를 취소하고 FAILED, 받으면 EXPORT-002")
    void failureAborts() {
        given(fileStorageService.openStream(anyString())).willThrow(new RuntimeException("s3 down"));
        ExportDtos.Export export = exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(null, null));

        exportWorker.run(id(export));

        assertThat(exportService.get(owner.getPublicId(), event.getPublicId(), export.exportId()).status())
                .isEqualTo(ExportStatus.FAILED);
        then(s3Client).should().abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        then(s3Client).should(never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        assertCode(() -> download(export), ExportErrorCode.GONE);
    }

    @Test
    @DisplayName("두 번째 사진에서 읽기가 실패해도(이미 파트가 올라간 뒤) multipart 를 완료하지 않고 취소한다")
    void midwayFailureNeverCompletes() {
        given(fileStorageService.openStream(anyString())).willAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.endsWith("2.jpg")) {
                throw new IllegalStateException("s3 read failed");
            }
            return new ByteArrayInputStream(new byte[4096]);   // 파트(1KB)보다 커서 실패 전에 파트가 올라간다
        });
        ExportDtos.Export export = exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(first.getId(), second.getId()), null));

        exportWorker.run(id(export));

        then(s3Client).should(atLeastOnce()).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        then(s3Client).should(never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        then(s3Client).should().abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        assertThat(exportService.get(owner.getPublicId(), event.getPublicId(), export.exportId()).status())
                .isEqualTo(ExportStatus.FAILED);
    }

    @Test
    @DisplayName("재시작 복구: RUNNING 은 FAILED 가 되고 그 key 삭제를 예약한다(완료된 객체가 남지 않게 — 실패하면 예약 큐가 다시 지운다)")
    void recoveryDeletesObjectOfRunningExport() {
        ExportDtos.Export running = exportService.create(owner.getPublicId(), event.getPublicId(), new ExportDtos.ExportRequest(null, null));
        Long runningId = id(running);
        exportJobRepository.claim(runningId, LocalDateTime.now());   // 업로드 완료 뒤 DONE 기록 전에 멈춘 상태
        String key = exportJobRepository.findById(runningId).orElseThrow().getS3Key();
        entityManager.flush();
        // 오래 멈춰 있다가 복구되는 경우 — 만든 지(그리고 마지막 갱신이) 3일 지났어도 재삭제 대상이어야 한다
        jdbcTemplate.update("update export_job set created_at = ?, updated_at = ? where export_job_id = ?",
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(3), runningId);
        entityManager.clear();

        exportWorker.recover();

        assertThat(exportService.get(owner.getPublicId(), event.getPublicId(), running.exportId()).status()).isEqualTo(ExportStatus.FAILED);
        then(s3Deleter).should().deleteHeldAfterCommit(key);
    }

    @Test
    @DisplayName("다른 사람의 묶음은 없는 것처럼 404, 묶음 권한이 없으면 403 PHOTO-001")
    void ownership() {
        ExportDtos.Export export = exportService.create(owner.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(null, null));
        User customer = userRepository.save(User.localUser("c@harucut.com", "e", "고객"));
        invitationService.accept(customer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS, PhotoPermission.BULK_DOWNLOAD), null)).inviteToken());

        assertCode(() -> exportService.get(customer.getPublicId(), event.getPublicId(), export.exportId()), GlobalErrorCode.NOT_FOUND);
        assertThat(exportService.mine(customer.getPublicId(), event.getPublicId())).isEmpty();

        User viewer = userRepository.save(User.localUser("v@harucut.com", "e", "열람"));
        invitationService.accept(viewer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken());
        assertCode(() -> exportService.create(viewer.getPublicId(), event.getPublicId(), new ExportDtos.ExportRequest(null, null)),
                PhotoErrorCode.PERMISSION_REQUIRED);
    }

    // ── helpers ──────────────────────────────

    private DownloadUrlResponse download(ExportDtos.Export export) {
        return exportService.download(owner.getPublicId(), event.getPublicId(), export.exportId());
    }

    private Long id(ExportDtos.Export export) {
        return exportJobRepository.findByPublicId(export.exportId()).orElseThrow().getId();
    }

    private ComposeJob done(Participant participant, String key, String resultKey) {
        ComposeJob job = job(participant, key);
        composeJobRepository.markDone(job.getId(), resultKey, ComposeStatus.PENDING, ComposeStatus.DONE);
        return job;
    }

    private ComposeJob job(Participant participant, String key) {
        return composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, 41L));
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes()));
            }
        }
        return entries;
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
