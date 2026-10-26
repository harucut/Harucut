package com.harucut.export.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.service.EventAccessPolicy;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.entity.ExportJob;
import com.harucut.export.entity.ExportJobItem;
import com.harucut.export.enums.ExportStatus;
import com.harucut.export.exception.ExportErrorCode;
import com.harucut.export.repository.ExportJobItemRepository;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 행사 사진 묶음 다운로드 (ADR-0008 §4). 접수만 하고 압축은 {@link ExportWorker} 가 커밋 뒤 따로 한다 —
 * API 요청 하나가 긴 압축을 맡지 않는다. 수령할 때마다 권한·담긴 사진을 다시 본다.
 */
@Service
@Transactional
public class ExportService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final EventAccessPolicy policy;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobItemRepository itemRepository;
    private final ComposeJobRepository composeJobRepository;
    private final FileStorageService fileStorageService;
    private final S3Deleter s3Deleter;
    private final StorageProperties storageProperties;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final EntitlementService entitlementService;
    private final Clock clock;
    private final int maxPhotos;

    public ExportService(EventAccessPolicy policy, ExportJobRepository exportJobRepository,
                         ExportJobItemRepository itemRepository, ComposeJobRepository composeJobRepository,
                         FileStorageService fileStorageService, S3Deleter s3Deleter, StorageProperties storageProperties,
                         UserRepository userRepository, AuditLogService auditLogService,
                         ApplicationEventPublisher eventPublisher, EntitlementService entitlementService, Clock clock,
                         @Value("${event.export.max-photos:2000}") int maxPhotos) {
        this.policy = policy;
        this.exportJobRepository = exportJobRepository;
        this.itemRepository = itemRepository;
        this.composeJobRepository = composeJobRepository;
        this.fileStorageService = fileStorageService;
        this.s3Deleter = s3Deleter;
        this.storageProperties = storageProperties;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.entitlementService = entitlementService;
        this.clock = clock;
        this.maxPhotos = maxPhotos;
    }

    public ExportDtos.Export create(String actorPublicId, String eventPublicId, ExportDtos.ExportRequest request) {
        Long actorId = userId(actorPublicId);
        Event event = policy.requirePhoto(actorId, eventPublicId, PhotoPermission.BULK_DOWNLOAD);
        entitlementService.requireFeature(event, Feature.BULK_DOWNLOAD);   // 계약 기능 — 없으면 403 ENT-004
        List<ComposeJob> targets = targets(event, request);
        if (targets.isEmpty() || targets.size() > maxPhotos) {
            throw new BusinessException(ExportErrorCode.INVALID_SELECTION,
                    "묶을 사진 " + targets.size() + "장 — 1장 이상 " + maxPhotos + "장 이하여야 합니다.");
        }
        ExportJob export = exportJobRepository.save(ExportJob.pending(event.getId(), event.getPublicId(), actorId, targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            itemRepository.save(new ExportJobItem(export.getId(), targets.get(i).getId(), i));
        }
        auditLogService.record(actorId, event.getWorkspaceId(), event.getId(), "EXPORT_CREATED", "EXPORT",
                export.getPublicId(), Map.of("photoCount", targets.size()));
        eventPublisher.publishEvent(new ExportRequestedEvent(export.getId()));
        return view(export);
    }

    @Transactional(readOnly = true)
    public ExportDtos.Export get(String actorPublicId, String eventPublicId, String exportPublicId) {
        Long actorId = userId(actorPublicId);
        Event event = policy.requirePhoto(actorId, eventPublicId, PhotoPermission.BULK_DOWNLOAD);
        return view(own(event, actorId, exportPublicId));
    }

    @Transactional(readOnly = true)
    public List<ExportDtos.Export> mine(String actorPublicId, String eventPublicId) {
        Long actorId = userId(actorPublicId);
        Event event = policy.requirePhoto(actorId, eventPublicId, PhotoPermission.BULK_DOWNLOAD);
        return exportJobRepository.findByEventIdAndRequestedByUserIdOrderByIdDesc(event.getId(), actorId, PageRequest.of(0, 20))
                .stream().map(this::view).toList();
    }

    /**
     * 수령 — 권한·상태·기한·담긴 사진을 다시 본다. 무효면 그 자리에서 EXPIRED 로 바꾸고 객체를 지운 뒤 410.
     * 그 변경이 예외로 롤백되지 않게 BusinessException 으로 롤백하지 않는다(판정 전에는 아무것도 쓰지 않는다).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public DownloadUrlResponse download(String actorPublicId, String eventPublicId, String exportPublicId) {
        Long actorId = userId(actorPublicId);
        Event event = policy.requirePhoto(actorId, eventPublicId, PhotoPermission.BULK_DOWNLOAD);
        ExportJob export = own(event, actorId, exportPublicId);
        switch (export.getStatus()) {
            case PENDING, RUNNING -> throw new BusinessException(ExportErrorCode.NOT_READY);
            case FAILED, EXPIRED -> throw new BusinessException(ExportErrorCode.GONE);
            case DONE -> {
            }
        }
        if (!LocalDateTime.now(clock).isBefore(export.getExpiresAt()) || itemRepository.containsDeleted(export.getId())) {
            expire(export);
            throw new BusinessException(ExportErrorCode.GONE);
        }
        String filename = event.getName() + "-" + export.getCreatedAt().atZone(clock.getZone()).format(FILE_DATE) + ".zip";
        String url = fileStorageService.generatePresignedDownloadUrl(export.getS3Key(), filename,
                storageProperties.download().getTtl());
        auditLogService.record(actorId, event.getWorkspaceId(), event.getId(), "EXPORT_DOWNLOADED", "EXPORT",
                export.getPublicId(), null);
        return new DownloadUrlResponse(url, offset(LocalDateTime.now(clock).plus(storageProperties.download().getTtl())));
    }

    /** 사진 삭제 — 그 사진을 담은 살아 있는 묶음을 무효로 하고 객체를 지운다(진행 중이면 워커가 완료하지 못하고 스스로 지운다) */
    public void invalidateContaining(Long composeJobId) {
        exportJobRepository.findLiveContaining(composeJobId).forEach(this::expire);
    }

    void expire(ExportJob export) {
        if (exportJobRepository.expire(export.getId()) == 1) {
            s3Deleter.deleteAfterCommit(List.of(export.getS3Key()));
        }
    }

    private List<ComposeJob> targets(Event event, ExportDtos.ExportRequest request) {
        // photoIds 를 보냈으면(빈 목록 포함) 명시 선택이다 — 빈 목록이 필터·전체로 떨어지면 안 된다(빈 선택은 EXPORT-003)
        if (request.photoIds() != null) {
            Map<Long, ComposeJob> found = composeJobRepository.findAllById(request.photoIds()).stream()
                    .filter(job -> event.getId().equals(job.getEventId()) && usable(job))
                    .collect(Collectors.toMap(ComposeJob::getId, Function.identity()));
            // 요청 순서를 지키고, 이 행사의 완성 사진이 아닌 id 는 조용히 뺀다
            return request.photoIds().stream().distinct().map(found::get).filter(Objects::nonNull).toList();
        }
        ExportDtos.ExportFilter filter = request.filter();
        List<Object[]> rows = composeJobRepository.findEventPhotos(event.getId(), ComposeStatus.DONE,
                filter == null ? null : filter.channel(), filter == null ? null : filter.frameVersionId(),
                filter == null ? null : local(filter.from()), filter == null ? null : local(filter.to()),
                null, null, PageRequest.of(0, maxPhotos + 1));
        List<ComposeJob> jobs = new ArrayList<>();
        rows.forEach(row -> jobs.add((ComposeJob) row[0]));
        return jobs.stream().filter(ExportService::usable).toList();
    }

    private static boolean usable(ComposeJob job) {
        return job.getStatus() == ComposeStatus.DONE && !job.isDeleted() && job.getResultKey() != null;
    }

    // 다른 사람이 만든 묶음은 없는 것처럼 — 404
    private ExportJob own(Event event, Long actorId, String exportPublicId) {
        return exportJobRepository.findByPublicId(exportPublicId)
                .filter(found -> found.getEventId().equals(event.getId()) && found.getRequestedByUserId().equals(actorId))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private ExportDtos.Export view(ExportJob export) {
        return new ExportDtos.Export(export.getPublicId(), export.getStatus(), export.getPhotoCount(), export.getSizeBytes(),
                export.getStatus() == ExportStatus.DONE ? offset(export.getExpiresAt()) : null, offset(export.getCreatedAt()));
    }

    private Long userId(String publicId) {
        return userRepository.findByPublicId(publicId).map(User::getId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
