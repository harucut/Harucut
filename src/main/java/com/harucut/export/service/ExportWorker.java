package com.harucut.export.service;

import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.export.entity.ExportJob;
import com.harucut.export.entity.ExportJobItem;
import com.harucut.export.enums.ExportScope;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 * 실패(→FAILED).
 *
 * <p>인스턴스가 여럿이어도(무중단 배포 중 겹침 포함) 안전하게: 압축 중에는 전송과 따로 도는 진행 신호(updatedAt)를 주기적으로 남기고, 정리는 신호가
 * staleAfter 넘게 끊긴 RUNNING 만 FAILED 로 바꾼다(재개하지 않는다). 종료할 때는 하던 것을 PENDING 으로 내려놓고, PENDING 은
 * 어느 인스턴스든 주기적으로 다시 넣는다 — 선점이 조건부라 두 곳이 넣어도 한 곳만 만든다.
 */
@Slf4j
@Component
public class ExportWorker {

    private final ExportJobRepository exportJobRepository;
    private final ExportJobItemRepository itemRepository;
    private final ComposeJobRepository composeJobRepository;
    private final EventRepository eventRepository;
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
    // 업로드 책임의 유효 시간 — 이보다 오래 걸리는 묶음은 없다(최대 2,000장). 지나면 재시도 배치가 그 key 를 지운다
    private final Duration uploadHold;
    // 진행 신호가 이만큼 끊기면 그 작업자는 죽은 것으로 본다. 신호는 이 간격의 1/5 마다 보낸다
    private final Duration staleAfter;
    // 이 인스턴스의 실행기에 이미 넣은 것 — 주기적으로 다시 넣을 때 같은 작업을 큐에 쌓지 않는다
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "export-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ExportWorker(ExportJobRepository exportJobRepository, ExportJobItemRepository itemRepository,
                        ComposeJobRepository composeJobRepository, EventRepository eventRepository,
                        FileStorageService fileStorageService, S3Deleter s3Deleter, PlatformTransactionManager transactionManager, Clock clock,
                        @Value("${event.export.ttl:24h}") Duration ttl,
                        @Value("${event.export.upload-hold:6h}") Duration uploadHold,
                        @Value("${event.export.stale-after:5m}") Duration staleAfter) {
        this.exportJobRepository = exportJobRepository;
        this.itemRepository = itemRepository;
        this.composeJobRepository = composeJobRepository;
        this.eventRepository = eventRepository;
        this.fileStorageService = fileStorageService;
        this.s3Deleter = s3Deleter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.ttl = ttl;
        this.uploadHold = uploadHold;
        this.staleAfter = staleAfter;
    }

    @TransactionalEventListener
    public void onRequested(ExportRequestedEvent event) {
        enqueue(event.exportJobId());
    }

    private void enqueue(Long exportJobId) {
        if (stopping || !queued.add(exportJobId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(exportJobId);
                } finally {
                    queued.remove(exportJobId);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            queued.remove(exportJobId);   // 종료 중 — PENDING 으로 남아 다른 인스턴스가 가져간다
        }
    }

    /** 묶음 하나를 만든다. 이미 누가 선점했거나 무효가 됐으면 아무것도 하지 않는다 */
    public void run(Long exportJobId) {
        Boolean claimed = transaction.execute(status -> exportJobRepository.claim(exportJobId, LocalDateTime.now(clock)) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        // 진행 신호는 전송과 따로 돈다 — 사진 하나의 읽기·쓰기가 아무리 오래 걸려도 살아 있는 동안은 신호가 끊기지 않는다
        long interval = staleAfter.dividedBy(5).toMillis();
        ScheduledFuture<?> beating = heartbeat.scheduleAtFixedRate(() -> beat(exportJobId), interval, interval, TimeUnit.MILLISECONDS);
        try {
            build(exportJobId);
        } finally {
            beating.cancel(false);
        }
    }

    // 조건부(RUNNING 일 때만) — 이미 FAILED·EXPIRED 로 바뀐 행은 되살리지 않는다. 실패해도 다음 박자에 다시(예외를 밖으로 내면 반복이 멈춘다)
    private void beat(Long exportJobId) {
        try {
            transaction.executeWithoutResult(status -> exportJobRepository.touch(exportJobId, LocalDateTime.now(clock)));
        } catch (RuntimeException e) {
            log.warn("[묶음] {} 진행 신호 실패", exportJobId, e);
        }
    }

    private void build(Long exportJobId) {
        ExportJob export = exportJobRepository.findById(exportJobId).orElseThrow();
        S3MultipartOutputStream upload = null;
        boolean uploaded = false;
        try {
            List<ExportJobItem> items = itemRepository.findAllByExportJobIdOrderBySortOrderAsc(exportJobId);
            Map<Long, ComposeJob> jobs = composeJobRepository.findAllById(items.stream().map(ExportJobItem::getComposeJobId).toList())
                    .stream().collect(Collectors.toMap(ComposeJob::getId, Function.identity()));
            // 올리기 전에 그 key 를 지울 책임부터 남긴다 — DONE 이 기록돼야 풀린다. 그 전에 멈추면 uploadHold 뒤 재시도 배치가 지운다
            transaction.executeWithoutResult(status -> s3Deleter.holdForUpload(export.getS3Key(), uploadHold));
            upload = fileStorageService.openMultipartUpload(export.getS3Key(), "application/zip");
            CountingOutputStream counted = new CountingOutputStream(upload);
            try (ZipOutputStream zip = new ZipOutputStream(counted)) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                // 여러 행사 묶음은 행사마다 폴더({slug}/), 번호도 행사 안에서 매긴다(ADR-0009 §6)
                boolean multi = export.getScope() != ExportScope.EVENT;
                Map<Long, String> slugs = !multi ? Map.of() : eventRepository.findAllById(jobs.values().stream()
                                .map(ComposeJob::getEventId).filter(java.util.Objects::nonNull).distinct().toList()).stream()
                        .collect(Collectors.toMap(Event::getId, Event::getSlug));
                Map<String, Integer> indexes = new java.util.HashMap<>();
                for (ExportJobItem item : items) {
                    if (stopping) {
                        throw new ShutdownInProgress();
                    }
                    ComposeJob job = jobs.get(item.getComposeJobId());
                    if (job == null || job.isDeleted() || job.getStatus() != ComposeStatus.DONE || job.getResultKey() == null) {
                        continue;   // 접수 뒤 지워진 사진 — 이 묶음은 수령 때 무효로 판정된다
                    }
                    String folder = multi ? slugs.getOrDefault(job.getEventId(), "event") + "/" : "";
                    int index = indexes.merge(folder, 1, Integer::sum);
                    zip.putNextEntry(new ZipEntry(folder + String.format("%04d-%d%s", index, job.getId(), extensionOf(job.getResultKey()))));
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
            Boolean completed = transaction.execute(status -> {
                boolean done = exportJobRepository.complete(exportJobId, size, LocalDateTime.now(clock).plus(ttl),
                        LocalDateTime.now(clock)) == 1;
                if (done) {
                    s3Deleter.release(export.getS3Key());   // DONE 과 같은 트랜잭션 — 둘 다 되거나 둘 다 안 된다
                } else {
                    // 압축하는 동안 무효가 됐다(사진 삭제 등) — 방금 올린 객체를 지금 지운다(실패해도 다시 지운다)
                    s3Deleter.deleteHeldAfterCommit(export.getS3Key());
                }
                return done;
            });
        } catch (Exception e) {
            if (stopping && !uploaded) {
                handBack(exportJobId, export, upload);
                return;
            }
            log.warn("[묶음] {} 실패", export.getPublicId(), e);
            if (uploaded) {
                // 객체는 완료됐는데 DONE 기록이 실패했다 — FAILED 로 남을 묶음의 객체를 지운다(만료 정리·무효화는 DONE 만 본다).
                // DB 장애일 수 있어 DB 없이 먼저 한 번 지우고, 아래 실패 기록과 함께 삭제도 예약한다(S3 가 실패했을 때 다시 지우게)
                deleteQuietly(export.getS3Key());
            } else if (upload != null) {
                try {
                    upload.abort();
                } catch (RuntimeException abortFailure) {
                    log.warn("[묶음] {} multipart 취소 실패 — 버킷 수명 규칙이 정리한다", export.getPublicId(), abortFailure);
                }
            }
            String reason = e.getClass().getSimpleName();
            transaction.executeWithoutResult(status -> {
                exportJobRepository.fail(exportJobId, reason, LocalDateTime.now(clock));
                // 업로드가 끝났든 아니든 지울 책임을 지금 처리한다(없는 객체 삭제는 아무 일도 하지 않는다)
                s3Deleter.deleteHeldAfterCommit(export.getS3Key());
            });
        }
    }

    // 종료 중 — 업로드를 취소하고 PENDING 으로 내려놓는다. 다시 만들 때는 새 key 로 올린다: 같은 key 를 다시 쓰면 앞 시도의
    // 업로드 책임(삭제 예약)이 lease 끝에 집혀 새 ZIP 을 DONE 전에 지우거나 그 행을 지워 크래시 보호를 없앨 수 있다(재시도 배치는
    // 행을 잠그지 않는다). 버려진 옛 key 는 더 올릴 것이 없으니 같은 트랜잭션에서 지울 차례로 당긴다
    private void handBack(Long exportJobId, ExportJob export, S3MultipartOutputStream upload) {
        Thread.interrupted();   // 종료 인터럽트 — 남아 있으면 커넥션을 빌리지 못한다
        if (upload != null) {
            try {
                upload.abort();
            } catch (RuntimeException abortFailure) {
                log.warn("[묶음] {} multipart 취소 실패 — 버킷 수명 규칙이 정리한다", export.getPublicId(), abortFailure);
            }
        }
        transaction.executeWithoutResult(status -> {
            if (exportJobRepository.requeue(exportJobId, ExportJob.retryKey(export.getS3Key()), LocalDateTime.now(clock)) == 1) {
                s3Deleter.deleteHeldAfterCommit(export.getS3Key());
            }
        });
        log.info("[묶음] {} 종료 중 — PENDING 으로 내려놓음", export.getPublicId());
    }

    // 기동할 때와 주기적으로 — 진행 신호가 끊긴 RUNNING 은 실패로(재개하지 않는다), PENDING 은 다시 넣는다.
    // 끊긴 RUNNING 은 업로드가 이미 완료된 뒤 DONE 기록 전에 멈춘 것일 수 있다 — FAILED 로 바꾸면서 그 key 의 삭제를 커밋 후 예약한다.
    // 없는 key 의 삭제는 아무 일도 하지 않는다. 미완 multipart 는 uploadId 를 남기지 않아 여기서 취소할 수 없다(버킷 수명 규칙 몫).
    // 다른 인스턴스가 압축 중인 것은 신호가 살아 있어 건드리지 않는다
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${event.export.recover-interval:1m}", initialDelayString = "${event.export.recover-interval:1m}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime before = now.minus(staleAfter);
        transaction.executeWithoutResult(status -> exportJobRepository.findAllByStatusAndUpdatedAtBefore(ExportStatus.RUNNING, before)
                .forEach(export -> {
                    if (exportJobRepository.failStale(export.getId(), before, "WORKER_LOST", now) == 1) {
                        // 멈추기 전 남긴 업로드 책임이 있으면 지금으로 당긴다 — 더 올리는 중인 것이 없다
                        s3Deleter.deleteHeldAfterCommit(export.getS3Key());
                    }
                }));
        exportJobRepository.findAllByStatus(ExportStatus.PENDING).forEach(export -> enqueue(export.getId()));
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

    private void deleteQuietly(String key) {
        try {
            fileStorageService.delete(key);
        } catch (RuntimeException deleteFailure) {
            log.warn("[묶음] 객체 {} 삭제 실패 — 수동 정리 필요", key, deleteFailure);
        }
    }

    // 종료 훅 — 새로 넣지 않고, 하던 것은 다음 사진 경계(또는 인터럽트)에서 PENDING 으로 내려놓게 한다.
    // DB 가 닫히기 전에 끝나도록 잠깐 기다린다(이 빈은 저장소에 의존해 그보다 먼저 정리된다)
    @PreDestroy
    public void shutdown() throws InterruptedException {
        stopping = true;
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("[묶음] 종료 대기 초과 — 진행 중이던 묶음은 신호가 끊긴 뒤 다른 인스턴스가 실패로 정리한다");
        }
        heartbeat.shutdownNow();
    }

    // 종료 중이라 압축을 멈춘다는 신호 — 실패가 아니다
    private static final class ShutdownInProgress extends RuntimeException {
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
