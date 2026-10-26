package com.harucut.export.service;

import com.harucut.export.entity.ExportJob;
import com.harucut.export.entity.ExportJobItem;
import com.harucut.export.enums.ExportStatus;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 묶음 압축기 (ADR-0008 §4). 동시 1개 실행기에서 결과 객체를 하나씩 읽어 ZIP 으로 흘려 S3 multipart 로 올린다 —
 * 메모리에는 파트 하나(8MB)와 읽는 중인 사진 버퍼만 둔다. JPEG 는 이미 압축돼 있어 다시 압축하지 않는다(레벨 0).
 *
 * <p>상태 전이는 전부 조건부 UPDATE: 선점(PENDING→RUNNING), 완료(RUNNING→DONE — 그사이 무효가 됐으면 0 이고 올린 객체를 지운다),
 * 실패(→FAILED). 서버가 재시작되면 RUNNING 은 FAILED 로 정리하고(재개하지 않는다) PENDING 은 다시 넣는다.
 */
@Slf4j
@Component
public class ExportWorker {

    private final ExportJobRepository exportJobRepository;
    private final ExportJobItemRepository itemRepository;
    private final ComposeJobRepository composeJobRepository;
    private final FileStorageService fileStorageService;
    private final S3Deleter s3Deleter;
    // 묶음 압축은 한 번에 하나 — 908MB 서버에서 큰 업로드 버퍼를 여러 개 띄우지 않는다.
    // 빈으로 두지 않는다: Executor 빈이 생기면 Boot 의 기본 applicationTaskExecutor 가 물러난다(ComposeResultConsumer 와 같은 선택)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "export-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration ttl;

    public ExportWorker(ExportJobRepository exportJobRepository, ExportJobItemRepository itemRepository,
                        ComposeJobRepository composeJobRepository, FileStorageService fileStorageService,
                        S3Deleter s3Deleter, PlatformTransactionManager transactionManager, Clock clock,
                        @Value("${event.export.ttl:24h}") Duration ttl) {
        this.exportJobRepository = exportJobRepository;
        this.itemRepository = itemRepository;
        this.composeJobRepository = composeJobRepository;
        this.fileStorageService = fileStorageService;
        this.s3Deleter = s3Deleter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.ttl = ttl;
    }

    @TransactionalEventListener
    public void onRequested(ExportRequestedEvent event) {
        executor.execute(() -> run(event.exportJobId()));
    }

    /** 묶음 하나를 만든다. 이미 누가 선점했거나 무효가 됐으면 아무것도 하지 않는다 */
    public void run(Long exportJobId) {
        Boolean claimed = transaction.execute(status -> exportJobRepository.claim(exportJobId, LocalDateTime.now(clock)) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        ExportJob export = exportJobRepository.findById(exportJobId).orElseThrow();
        S3MultipartOutputStream upload = null;
        boolean uploaded = false;
        try {
            List<ExportJobItem> items = itemRepository.findAllByExportJobIdOrderBySortOrderAsc(exportJobId);
            Map<Long, ComposeJob> jobs = composeJobRepository.findAllById(items.stream().map(ExportJobItem::getComposeJobId).toList())
                    .stream().collect(Collectors.toMap(ComposeJob::getId, Function.identity()));
            upload = fileStorageService.openMultipartUpload(export.getS3Key(), "application/zip");
            CountingOutputStream counted = new CountingOutputStream(upload);
            try (ZipOutputStream zip = new ZipOutputStream(counted)) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                int index = 1;
                for (ExportJobItem item : items) {
                    ComposeJob job = jobs.get(item.getComposeJobId());
                    if (job == null || job.isDeleted() || job.getStatus() != ComposeStatus.DONE || job.getResultKey() == null) {
                        continue;   // 접수 뒤 지워진 사진 — 이 묶음은 수령 때 무효로 판정된다
                    }
                    zip.putNextEntry(new ZipEntry(String.format("%04d-%d%s", index++, job.getId(), extensionOf(job.getResultKey()))));
                    try (InputStream in = fileStorageService.openStream(job.getResultKey())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            // ZIP 을 닫아도 업로드는 끝나지 않는다(CountingOutputStream 이 close 를 막는다) — 다 쓴 뒤에만 여기서 완료한다.
            // 위에서 예외가 나면 이 줄에 닿지 않고 아래 catch 가 취소한다
            upload.close();
            uploaded = true;
            long size = counted.count;
            Boolean completed = transaction.execute(status ->
                    exportJobRepository.complete(exportJobId, size, LocalDateTime.now(clock).plus(ttl), LocalDateTime.now(clock)) == 1);
            if (!Boolean.TRUE.equals(completed)) {
                // 압축하는 동안 무효가 됐다(사진 삭제 등) — 방금 올린 객체를 지운다
                fileStorageService.delete(export.getS3Key());
            }
        } catch (Exception e) {
            log.warn("[묶음] {} 실패", export.getPublicId(), e);
            if (uploaded) {
                // 객체는 완료됐는데 DONE 기록이 실패했다 — FAILED 로 남을 묶음의 객체를 지운다(만료 정리·무효화는 DONE 만 본다)
                deleteQuietly(export.getS3Key());
            } else if (upload != null) {
                try {
                    upload.abort();
                } catch (RuntimeException abortFailure) {
                    log.warn("[묶음] {} multipart 취소 실패 — 버킷 수명 규칙이 정리한다", export.getPublicId(), abortFailure);
                }
            }
            String reason = e.getClass().getSimpleName();
            transaction.executeWithoutResult(status -> exportJobRepository.fail(exportJobId, reason, LocalDateTime.now(clock)));
        }
    }

    // 서버 재시작 — 진행 중이던 것은 재개하지 않고 실패로, 아직 시작 못 한 것은 다시 넣는다.
    // RUNNING 은 업로드가 이미 완료된 뒤 DONE 기록 전에 멈춘 것일 수 있다 — FAILED 로 바꾸면서 그 key 의 삭제를 커밋 후 예약한다.
    // 없는 key 의 삭제는 아무 일도 하지 않는다. 미완 multipart 는 uploadId 를 남기지 않아 여기서 취소할 수 없다(버킷 수명 규칙 몫)
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        transaction.executeWithoutResult(status -> exportJobRepository.findAllByStatus(ExportStatus.RUNNING)
                .forEach(export -> {
                    if (exportJobRepository.fail(export.getId(), "SERVER_RESTARTED", LocalDateTime.now(clock)) == 1) {
                        s3Deleter.deleteAfterCommit(List.of(export.getS3Key()));
                    }
                }));
        List<ExportJob> pending = exportJobRepository.findAllByStatus(ExportStatus.PENDING);
        pending.forEach(export -> executor.execute(() -> run(export.getId())));
    }

    // 기한이 지난 묶음의 객체를 지우고 EXPIRED 로
    @Scheduled(fixedDelayString = "${event.export.sweep-interval:10m}")
    public void sweepExpired() {
        transaction.executeWithoutResult(status -> exportJobRepository
                .findAllByStatusAndExpiresAtBefore(ExportStatus.DONE, LocalDateTime.now(clock))
                .forEach(export -> {
                    if (exportJobRepository.expire(export.getId(), LocalDateTime.now(clock)) == 1) {
                        s3Deleter.deleteAfterCommit(List.of(export.getS3Key()));
                    }
                }));
    }

    /**
     * 커밋 뒤 삭제(S3Deleter)는 서버 중단·S3 오류가 나면 다시 시도하지 않는다 — 실패·만료한 지 이틀 안 된 묶음의 객체 삭제를
     * 매일 다시 예약한다. 없는 key 의 삭제는 아무 일도 하지 않으므로 몇 번 겹쳐도 같다
     */
    @Scheduled(cron = "${event.export.reconcile-cron:0 30 4 * * *}")
    public void reconcileDeletes() {
        transaction.executeWithoutResult(status -> {
            List<String> keys = exportJobRepository.findAllByStatusInAndUpdatedAtAfter(
                            EnumSet.of(ExportStatus.FAILED, ExportStatus.EXPIRED), LocalDateTime.now(clock).minusDays(2))
                    .stream().map(ExportJob::getS3Key).toList();
            s3Deleter.deleteAfterCommit(keys);
        });
    }

    private void deleteQuietly(String key) {
        try {
            fileStorageService.delete(key);
        } catch (RuntimeException deleteFailure) {
            log.warn("[묶음] 객체 {} 삭제 실패 — 수동 정리 필요", key, deleteFailure);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? ".jpg" : key.substring(dot);
    }

    // ZIP 크기를 세려고 — 업로드 스트림은 길이를 알려 주지 않는다.
    // close 를 밑으로 넘기지 않는다: ZipOutputStream 을 닫으면(try-with-resources, 실패 경로 포함) 밑의 multipart 가
    // 「완료」돼 버리고, 그 뒤의 abort 는 이미 완료된 객체를 지우지 못한다. 완료는 성공 경로에서 명시적으로만 한다
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
