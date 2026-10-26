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
import java.util.function.Supplier;

/**
 * 행사 결과 보관 종료 (ADR-0008 §7). 단계마다·행사마다 트랜잭션을 나눠, 한 행사의 실패가 다른 행사를 막지 않게 한다.
 * <ol>
 *   <li>보관 기한(작업 생성 + 행사 resultRetentionDays)이 지난 결과·썸네일 객체를 커밋 후 지우고 purged_at 을 남긴다 —
 *       사진함·묶음·결과 링크에서 EXPIRED. 그 사진을 담은 묶음은 무효로 한다.</li>
 *   <li>삭제 요청·보관 종료 이틀 안의 결과 객체 삭제를 다시 예약한다 — 커밋 뒤 삭제가 서버 중단·S3 오류로 빠진 것을 메운다.</li>
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
    private final Duration captureInputReplay;

    public EventRetentionService(EventRepository eventRepository, ComposeJobRepository composeJobRepository,
                                 AssetRepository assetRepository, ResultAccessRepository resultAccessRepository,
                                 ParticipantSessionRepository sessionRepository, BoothDeviceRepository boothDeviceRepository,
                                 ExportService exportService, S3Deleter s3Deleter, PlatformTransactionManager transactionManager,
                                 Clock clock, @Value("${event.retention.batch-size:500}") int batchSize,
                                 @Value("${event.retention.cleanup-after-archive:30d}") Duration cleanupAfterArchive,
                                 @Value("${event.retention.capture-input-replay:30d}") Duration captureInputReplay) {
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
        this.captureInputReplay = captureInputReplay;
    }

    public Result run() {
        int purged = 0;
        for (Event event : eventRepository.findAllByStatusIn(EnumSet.of(EventStatus.LIVE, EventStatus.CAPTURE_CLOSED))) {
            purged += isolated("결과 정리", event, () -> purge(event.getId()), 0);
        }
        // 재생 기간이 지난 작업의 입력 행만 — 그 안에서는 같은 합성 요청을 다시 보내면 기존 작업을 돌려줘야 한다
        Integer inputs = transaction.execute(status -> assetRepository.deleteSettledCaptureInputs(now().minus(captureInputReplay)));
        int archived = 0;
        for (Event event : eventRepository.findAllByStatusIn(EnumSet.of(EventStatus.LIVE, EventStatus.CAPTURE_CLOSED))) {
            if (isolated("보관 종료", event, () -> archiveIfDone(event.getId()), false)) {
                archived++;
            }
        }
        int cleaned = 0;
        for (Event event : eventRepository.findAllByStatusAndArchivedAtBefore(EventStatus.ARCHIVED, now().minus(cleanupAfterArchive))) {
            if (isolated("부가 행 정리", event, () -> {
                cleanupArchived(event.getId());
                return true;
            }, false)) {
                cleaned++;
            }
        }
        return new Result(purged, inputs == null ? 0 : inputs, archived, cleaned);
    }

    // 한 행사의 실패가 다른 행사를 막지 않게 — 실패는 남기고 다음 실행이 다시 본다.
    // 보관 종료는 Event 의 @Version 낙관적 잠금이라, 같은 순간 운영자가 설정을 바꿨으면 여기서 지고 다음 실행에 다시 시도된다
    private <T> T isolated(String step, Event event, Supplier<T> work, T fallback) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            log.warn("[행사 보관] {} 실패 — 행사 {} 는 다음 실행에서 다시 본다", step, event.getPublicId(), e);
            return fallback;
        }
    }

    // 한 행사의 기한 지난 결과 — 배치 크기씩 끝까지. 영구 삭제라 기준 시각은 배치마다 트랜잭션 안에서 지금 설정으로 다시 계산한다
    private int purge(Long eventId) {
        int total = 0;
        while (true) {
            Integer done = transaction.execute(status -> {
                // 잠금 읽기 — 실행 처음에 읽은 행사로 계산하면, 그 뒤 운영자가 보관 일수를 늘려도 예전 기준으로 지운다.
                // 이 배치가 커밋할 때까지 설정 저장이 기다리고, 먼저 커밋된 저장은 여기서 보인다
                Integer retentionDays = eventRepository.lockRetentionDays(eventId).stream().findFirst().orElse(null);
                if (retentionDays == null) {
                    return 0;
                }
                LocalDateTime now = now();
                LocalDateTime cutoff = now.minusDays(retentionDays);
                List<ComposeJob> jobs = composeJobRepository.findPurgeable(eventId, cutoff, PageRequest.of(0, batchSize));
                for (ComposeJob job : jobs) {
                    // purged_at 만 원자적으로 — 엔티티를 고쳐 저장하면 그사이 커밋된 삭제 요청(deleted_at)을 덮는다
                    if (composeJobRepository.markPurged(job.getId(), now) == 1) {
                        s3Deleter.deleteAfterCommit(List.of(job.getResultKey(), ComposeService.thumbnailKeyFor(job)));
                        exportService.invalidateContaining(job.getId());
                    }
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
            // 행사 행을 쓰기 잠금으로 — 촬영 열림 검사를 통과하고 아직 커밋 전인 접수(공유 잠금)가 끝나기를 기다리고,
            // 이 판정이 커밋될 때까지 새 접수가 촬영 열림을 확인하지 못하게 한다. 작업 존재도 잠금 읽기로 다시 본다
            Event event = eventRepository.lockById(eventId).orElse(null);
            if (event == null) {
                return false;
            }
            // 상시 캠페인은 운영자가 촬영을 끝내야(CAPTURE_CLOSED) 대상이 된다 — 종료 시각으로 저절로 닫지 않는다(ADR-0009 §4)
            boolean captureOver = event.getStatus() == EventStatus.CAPTURE_CLOSED
                    || (!event.isAlwaysOn() && event.getCaptureEndsAt() != null && !now().isBefore(event.getCaptureEndsAt()));
            if (!captureOver || !composeJobRepository.lockVisibleJobIds(eventId).isEmpty()) {
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
