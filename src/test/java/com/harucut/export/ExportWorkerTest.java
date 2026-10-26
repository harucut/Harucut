package com.harucut.export;

import com.harucut.export.entity.ExportJob;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.export.service.ExportWorker;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
                s3Deleter, mock(PlatformTransactionManager.class), Clock.systemUTC(), Duration.ofHours(24), Duration.ofHours(6));
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
                s3Deleter, mock(PlatformTransactionManager.class), Clock.systemUTC(), Duration.ofHours(24), Duration.ofHours(6)).run(7L);

        then(s3Deleter).should().holdForUpload(export.getS3Key(), Duration.ofHours(6));
        then(s3Deleter).should().deleteHeldAfterCommit(export.getS3Key());   // 미뤄 둔 책임을 지금으로
        then(s3Deleter).should(never()).release(anyString());
        then(fileStorageService).should(never()).delete(anyString());
        then(exportJobRepository).should(never()).fail(anyLong(), anyString(), any());
    }
}
