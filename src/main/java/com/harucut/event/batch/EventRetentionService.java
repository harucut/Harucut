package com.harucut.event.batch;

import com.harucut.asset.repository.AssetRepository;
import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventRepository;
import com.harucut.export.service.ExportService;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.result.repository.ResultAccessRepository;
import com.harucut.storage.service.S3Deleter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * 행사 결과 보관 종료 (ADR-0008 §7). 단계마다·행사마다 트랜잭션을 나눠, 한 행사의 실패가 다른 행사를 막지 않게 한다.
 * <ol>
 *   <li>보관 기한(작업 생성 + 행사 resultRetentionDays)이 지난 결과·썸네일 객체를 커밋 후 지우고 purged_at 을 남긴다 —
 *       사진함·묶음·결과 링크에서 EXPIRED. 그 사진을 담은 묶음은 무효로 한다.</li>
 *   <li>결과가 확정된 작업에 묶였던 촬영 입력 행을 지운다(객체는 확정 때 이미 지웠다).</li>
 *   <li>촬영이 끝났고 보일 결과가 남지 않은 행사를 ARCHIVED 로.</li>
 *   <li>ARCHIVED 뒤 30일이 지난 행사의 참가자 세션·결과 토큰 행을 지우고 부스 기기를 회수한다.
 *       참가자·회차·동의 기록은 남긴다(동의는 근거 기록이다).</li>
 * </ol>
 * 지우기가 멱등이라 서버 두 대가 함께 돌아도 결과가 같다.
 */
@Slf4j
@Component
public class EventRetentionService {

    private final EventRepository eventRepository;
    private final ComposeJobRepository composeJobRepository;
    private final AssetRepository assetRepository;
    private final ResultAccessRepository resultAccessRepository;
    private final ParticipantSessionRepository sessionRepository;
    private final BoothDeviceRepository boothDeviceRepository;
    private final ExportService exportService;
    private final S3Deleter s3Deleter;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final Duration cleanupAfterArchive;

    public EventRetentionService(EventRepository eventRepository, ComposeJobRepository composeJobRepository,
                                 AssetRepository assetRepository, ResultAccessRepository resultAccessRepository,
                                 ParticipantSessionRepository sessionRepository, BoothDeviceRepository boothDeviceRepository,
                                 ExportService exportService, S3Deleter s3Deleter, PlatformTransactionManager transactionManager,
                                 Clock clock, @Value("${event.retention.batch-size:500}") int batchSize,
                                 @Value("${event.retention.cleanup-after-archive:30d}") Duration cleanupAfterArchive) {
        this.eventRepository = eventRepository;
        this.composeJobRepository = composeJobRepository;
        this.assetRepository = assetRepository;
        this.resultAccessRepository = resultAccessRepository;
        this.sessionRepository = sessionRepository;
        this.boothDeviceRepository = boothDeviceRepository;
        this.exportService = exportService;
        this.s3Deleter = s3Deleter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.cleanupAfterArchive = cleanupAfterArchive;
    }

    public Result run() {
        int purged = 0;
        for (Event event : eventRepository.findAllByStatusIn(EnumSet.of(EventStatus.LIVE, EventStatus.CAPTURE_CLOSED))) {
            purged += purge(event);
        }
        Integer inputs = transaction.execute(status -> assetRepository.deleteSettledCaptureInputs());
        int archived = 0;
        for (Event event : eventRepository.findAllByStatusIn(EnumSet.of(EventStatus.LIVE, EventStatus.CAPTURE_CLOSED))) {
            if (archiveIfDone(event.getId())) {
                archived++;
            }
        }
        int cleaned = 0;
        for (Event event : eventRepository.findAllByStatusAndArchivedAtBefore(EventStatus.ARCHIVED, now().minus(cleanupAfterArchive))) {
            cleanupArchived(event.getId());
            cleaned++;
        }
        return new Result(purged, inputs == null ? 0 : inputs, archived, cleaned);
    }

    // 한 행사의 기한 지난 결과 — 배치 크기씩 끝까지
    private int purge(Event event) {
        LocalDateTime cutoff = now().minusDays(event.getResultRetentionDays());
        int total = 0;
        while (true) {
            Integer done = transaction.execute(status -> {
                List<ComposeJob> jobs = composeJobRepository.findPurgeable(event.getId(), cutoff, PageRequest.of(0, batchSize));
                LocalDateTime now = now();
                for (ComposeJob job : jobs) {
                    job.markPurged(now);
                    s3Deleter.deleteAfterCommit(List.of(job.getResultKey(), ComposeService.thumbnailKeyFor(job)));
                    exportService.invalidateContaining(job.getId());
                }
                return jobs.size();
            });
            total += done == null ? 0 : done;
            if (done == null || done < batchSize) {
                return total;
            }
        }
    }

    // 촬영이 끝났고(종료 상태이거나 종료 시각이 지났고) 보일 결과·진행 중 작업이 없으면 ARCHIVED
    private boolean archiveIfDone(Long eventId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) {
                return false;
            }
            boolean captureOver = event.getStatus() == EventStatus.CAPTURE_CLOSED
                    || (event.getCaptureEndsAt() != null && !now().isBefore(event.getCaptureEndsAt()));
            if (!captureOver || composeJobRepository.hasVisibleResults(eventId)) {
                return false;
            }
            event.archive(now());
            return true;
        }));
    }

    private void cleanupArchived(Long eventId) {
        transaction.executeWithoutResult(status -> {
            sessionRepository.deleteByEventId(eventId);
            resultAccessRepository.deleteByEventId(eventId);
            boothDeviceRepository.findAllByEventIdOrderByIdAsc(eventId).stream()
                    .filter(device -> !device.isRevoked())
                    .forEach(BoothDevice::revoke);
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record Result(int purgedResults, int deletedCaptureInputs, int archivedEvents, int cleanedEvents) {
    }
}
