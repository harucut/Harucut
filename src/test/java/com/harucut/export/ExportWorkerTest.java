package com.harucut.export;

import com.harucut.export.entity.ExportJob;
import com.harucut.export.entity.ExportJobItem;
import com.harucut.export.enums.ExportStatus;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.export.service.ExportWorker;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("ExportWorker — 완료 뒤 기록 실패")
class ExportWorkerTest {

    @Test
    @DisplayName("multipart 는 완료됐는데 DONE 기록이 실패하면 완료된 객체를 지운다 — 취소는 완료된 객체를 못 지운다")
    void deletesCompletedObjectWhenDoneRecordFails() {
        ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
        ExportJobItemRepository itemRepository = mock(ExportJobItemRepository.class);
        ComposeJobRepository composeJobRepository = mock(ComposeJobRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        S3Client s3Client = mock(S3Client.class);
        ExportJob export = ExportJob.pending(9L, 1L, "Ev1", 2L, 1);

        given(exportJobRepository.claim(eq(7L), any())).willReturn(1);
        given(exportJobRepository.findById(7L)).willReturn(Optional.of(export));
        given(exportJobRepository.complete(anyLong(), anyLong(), any(), any())).willThrow(new IllegalStateException("db down"));
        given(itemRepository.findAllByExportJobIdOrderBySortOrderAsc(7L)).willReturn(List.of());
        given(composeJobRepository.findAllById(any())).willReturn(List.of());
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .willReturn(UploadPartResponse.builder().eTag("e").build());
        given(fileStorageService.openMultipartUpload(anyString(), anyString()))
                .willAnswer(i -> new S3MultipartOutputStream(s3Client, "bucket", i.getArgument(0), "application/zip", 1024));

        S3Deleter s3Deleter = mock(S3Deleter.class);
        ExportWorker worker = new ExportWorker(exportJobRepository, itemRepository, composeJobRepository, mock(com.harucut.event.repository.EventRepository.class), fileStorageService,
                s3Deleter, mock(PlatformTransactionManager.class), Clock.systemUTC(), Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(5));
        worker.run(7L);

        then(s3Client).should().completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        then(s3Deleter).should().holdForUpload(export.getS3Key(), Duration.ofHours(6));   // 올리기 전에 지울 책임부터
        then(fileStorageService).should().delete(export.getS3Key());   // DB 없이 먼저 한 번
        then(s3Deleter).should().deleteHeldAfterCommit(export.getS3Key());   // 실패 기록과 함께 — S3 가 실패해도 다시 지운다
        then(s3Deleter).should(never()).release(anyString());
        then(s3Client).should(never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        then(exportJobRepository).should().fail(eq(7L), eq("IllegalStateException"), any());
    }
    @Test
    @DisplayName("압축하는 동안 무효가 돼 완료 기록이 0행이면 올린 객체의 삭제를 예약한다(직접 지우지 않는다 — 실패해도 다시 지우게)")
    void schedulesDeletionWhenInvalidatedDuringUpload() {
        ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
        ExportJobItemRepository itemRepository = mock(ExportJobItemRepository.class);
        ComposeJobRepository composeJobRepository = mock(ComposeJobRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        S3Client s3Client = mock(S3Client.class);
        S3Deleter s3Deleter = mock(S3Deleter.class);
        ExportJob export = ExportJob.pending(9L, 1L, "Ev1", 2L, 1);

        given(exportJobRepository.claim(eq(7L), any())).willReturn(1);
        given(exportJobRepository.findById(7L)).willReturn(Optional.of(export));
        given(exportJobRepository.complete(anyLong(), anyLong(), any(), any())).willReturn(0);
        given(itemRepository.findAllByExportJobIdOrderBySortOrderAsc(7L)).willReturn(List.of());
        given(composeJobRepository.findAllById(any())).willReturn(List.of());
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .willReturn(UploadPartResponse.builder().eTag("e").build());
        given(fileStorageService.openMultipartUpload(anyString(), anyString()))
                .willAnswer(i -> new S3MultipartOutputStream(s3Client, "bucket", i.getArgument(0), "application/zip", 1024));

        new ExportWorker(exportJobRepository, itemRepository, composeJobRepository, mock(com.harucut.event.repository.EventRepository.class), fileStorageService,
                s3Deleter, mock(PlatformTransactionManager.class), Clock.systemUTC(), Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(5)).run(7L);

        then(s3Deleter).should().holdForUpload(export.getS3Key(), Duration.ofHours(6));
        then(s3Deleter).should().deleteHeldAfterCommit(export.getS3Key());   // 미뤄 둔 책임을 지금으로
        then(s3Deleter).should(never()).release(anyString());
        then(fileStorageService).should(never()).delete(anyString());
        then(exportJobRepository).should(never()).fail(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("종료 훅: 압축 중이면 다음 사진 경계에서 멈추고 업로드를 취소한 뒤 새 key 로 PENDING — 실패로 기록하지 않고, 버린 옛 key 만 지울 차례로")
    void handsBackOnShutdown() {
        Fixture f = new Fixture(Clock.systemUTC());
        String oldKey = f.export.getS3Key();
        given(f.exportJobRepository.requeue(eq(7L), anyString(), any())).willReturn(1);
        given(f.fileStorageService.openStream(anyString())).willAnswer(i -> {
            f.worker.shutdown();   // 첫 사진을 읽는 동안 종료가 시작된다
            return new ByteArrayInputStream(new byte[]{1});
        });

        f.worker.run(7L);

        ArgumentCaptor<String> newKey = ArgumentCaptor.forClass(String.class);
        then(f.exportJobRepository).should().requeue(eq(7L), newKey.capture(), any());
        assertThat(newKey.getValue()).isNotEqualTo(oldKey).startsWith("uploads/events/Ev1/exports/").endsWith(".zip");
        then(f.s3Deleter).should().deleteHeldAfterCommit(oldKey);   // 옛 key 는 더 올릴 것이 없다
        then(f.s3Deleter).should(never()).deleteHeldAfterCommit(newKey.getValue());
        then(f.s3Client).should().abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        then(f.s3Client).should(never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        then(f.exportJobRepository).should(never()).fail(anyLong(), anyString(), any());
        then(f.fileStorageService).should(org.mockito.Mockito.times(1)).openStream(anyString());   // 두 번째 사진은 읽지 않는다
    }

    @Test
    @DisplayName("다시 만들 key 는 같은 폴더·시도마다 다른 꼬리 — 여러 번 내려놓아도 꼬리가 쌓이지 않는다")
    void retryKey() {
        String first = ExportJob.retryKey("uploads/events/Ev1/exports/AbC.zip");
        String second = ExportJob.retryKey(first);
        assertThat(first).matches("uploads/events/Ev1/exports/AbC-r[A-Za-z0-9_-]{8}\\.zip");
        assertThat(second).matches("uploads/events/Ev1/exports/AbC-r[A-Za-z0-9_-]{8}\\.zip").isNotEqualTo(first);
    }

    @Test
    @DisplayName("진행 신호는 전송과 따로 돈다: 사진 하나를 읽는 데 staleAfter 보다 오래 걸려도 그동안 계속 신호를 보내고, 끝나면 멈춘다")
    void heartbeatsDuringSlowTransfer() throws InterruptedException {
        Fixture f = new Fixture(Clock.systemUTC(), Duration.ofMillis(100));   // 신호 간격 20ms
        given(f.exportJobRepository.complete(anyLong(), anyLong(), any(), any())).willReturn(1);
        given(f.fileStorageService.openStream(anyString())).willAnswer(i -> {
            Thread.sleep(300);   // 사진 하나가 staleAfter(100ms)의 세 배 — 사진 사이에서만 신호를 보내면 여기서 끊긴다
            return new ByteArrayInputStream(new byte[]{1});
        });
        AtomicInteger beatsDuringFirstPhoto = new AtomicInteger();
        given(f.exportJobRepository.touch(eq(7L), any())).willAnswer(i -> {
            beatsDuringFirstPhoto.incrementAndGet();
            return 1;
        });

        f.worker.run(7L);
        int afterRun = beatsDuringFirstPhoto.get();
        Thread.sleep(100);

        assertThat(afterRun).isGreaterThanOrEqualTo(10);   // 600ms 동안 20ms 간격 — 넉넉히 잡아도 10번 이상
        assertThat(beatsDuringFirstPhoto.get()).isEqualTo(afterRun);   // 끝난 뒤에는 보내지 않는다
    }

    @Test
    @DisplayName("정리: 진행 신호가 staleAfter 넘게 끊긴 RUNNING 만 조건부로 실패시키고 업로드 책임을 당긴다 — 읽은 뒤 신호가 와서 0행이면 건드리지 않는다")
    void recoverOnlyStale() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:10:00Z"), ZoneOffset.UTC);
        Fixture f = new Fixture(clock);
        ExportJob lost = ExportJob.pending(9L, 1L, "Ev1", 2L, 1);
        ExportJob raced = ExportJob.pending(9L, 1L, "Ev2", 2L, 1);
        org.springframework.test.util.ReflectionTestUtils.setField(lost, "id", 11L);
        org.springframework.test.util.ReflectionTestUtils.setField(raced, "id", 12L);
        LocalDateTime before = LocalDateTime.parse("2026-10-26T00:05:00");
        given(f.exportJobRepository.findAllByStatusAndUpdatedAtBefore(ExportStatus.RUNNING, before)).willReturn(List.of(lost, raced));
        given(f.exportJobRepository.failStale(eq(11L), eq(before), anyString(), any())).willReturn(1);
        given(f.exportJobRepository.failStale(eq(12L), eq(before), anyString(), any())).willReturn(0);
        given(f.exportJobRepository.findAllByStatus(ExportStatus.PENDING)).willReturn(List.of());

        f.worker.recover();

        then(f.s3Deleter).should().deleteHeldAfterCommit(lost.getS3Key());
        then(f.s3Deleter).should(never()).deleteHeldAfterCommit(raced.getS3Key());
        then(f.exportJobRepository).should(never()).fail(anyLong(), anyString(), any());
    }

    // 사진 2장짜리 묶음 7번을 압축하는 작업자
    private static final class Fixture {
        final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
        final ExportJobItemRepository itemRepository = mock(ExportJobItemRepository.class);
        final ComposeJobRepository composeJobRepository = mock(ComposeJobRepository.class);
        final FileStorageService fileStorageService = mock(FileStorageService.class);
        final S3Client s3Client = mock(S3Client.class);
        final S3Deleter s3Deleter = mock(S3Deleter.class);
        final ExportWorker worker;
        final ExportJob export = ExportJob.pending(9L, 1L, "Ev1", 2L, 2);

        Fixture(Clock clock) {
            this(clock, Duration.ofMinutes(5));
        }

        Fixture(Clock clock, Duration staleAfter) {
            given(exportJobRepository.claim(eq(7L), any())).willReturn(1);
            given(exportJobRepository.findById(7L)).willReturn(Optional.of(export));
            given(itemRepository.findAllByExportJobIdOrderBySortOrderAsc(7L))
                    .willReturn(List.of(new ExportJobItem(7L, 1L, 0), new ExportJobItem(7L, 2L, 1)));
            List<ComposeJob> photos = List.of(photo(1L), photo(2L));   // 스터빙 안에서 다른 목을 스터빙하지 않게 먼저
            given(composeJobRepository.findAllById(any())).willReturn(photos);
            given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                    .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
            given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                    .willReturn(UploadPartResponse.builder().eTag("e").build());
            given(fileStorageService.openMultipartUpload(anyString(), anyString()))
                    .willAnswer(i -> new S3MultipartOutputStream(s3Client, "bucket", i.getArgument(0), "application/zip", 1024));
            worker = new ExportWorker(exportJobRepository, itemRepository, composeJobRepository,
                    mock(com.harucut.event.repository.EventRepository.class), fileStorageService, s3Deleter,
                    mock(PlatformTransactionManager.class), clock, Duration.ofHours(24), Duration.ofHours(6), staleAfter);
        }

        private static ComposeJob photo(Long id) {
            ComposeJob job = mock(ComposeJob.class);
            given(job.getId()).willReturn(id);
            given(job.getStatus()).willReturn(ComposeStatus.DONE);
            given(job.getResultKey()).willReturn("uploads/events/e/results/" + id + ".jpg");
            return job;
        }
    }
}
