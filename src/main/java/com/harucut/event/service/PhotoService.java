package com.harucut.event.service;

import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.PhotoDtos;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.export.service.ExportService;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 행사 사진함 (ADR-0008 §3) — 목록·개별 다운로드·삭제. 매번 {@link EventAccessPolicy#requirePhoto} 로 권한을 다시 본다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PhotoService {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    private final EventAccessPolicy policy;
    private final ComposeJobRepository composeJobRepository;
    private final FileStorageService fileStorageService;
    private final S3Deleter s3Deleter;
    private final StorageProperties storageProperties;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ExportService exportService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PhotoDtos.PhotoPage page(String actorPublicId, String eventPublicId, String cursor, Integer limit,
                                    OffsetDateTime from, OffsetDateTime to, ParticipantChannel channel,
                                    Long frameVersionId, ComposeStatus status) {
        Event event = policy.requirePhoto(userId(actorPublicId), eventPublicId, PhotoPermission.VIEW_PHOTOS);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Cursor after = Cursor.decode(cursor);
        List<Object[]> rows = composeJobRepository.findEventPhotos(event.getId(), status, channel, frameVersionId,
                local(from), local(to), after == null ? null : after.createdAt(), after == null ? null : after.id(),
                PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        List<PhotoDtos.Photo> items = new ArrayList<>();
        for (Object[] row : rows.subList(0, Math.min(size, rows.size()))) {
            items.add(photo((ComposeJob) row[0], (ParticipantChannel) row[1]));
        }
        String next = more ? new Cursor(((ComposeJob) rows.get(size - 1)[0]).getCreatedAt(),
                ((ComposeJob) rows.get(size - 1)[0]).getId()).encode() : null;
        return new PhotoDtos.PhotoPage(items, next);
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse download(String actorPublicId, String eventPublicId, Long photoId) {
        Event event = policy.requirePhoto(userId(actorPublicId), eventPublicId, PhotoPermission.DOWNLOAD_PHOTOS);
        ComposeJob job = photoOf(event, photoId);
        if (job.getStatus() != ComposeStatus.DONE || job.getResultKey() == null) {
            throw new BusinessException(ResultErrorCode.NOT_READY);
        }
        String filename = event.getName() + "-" + job.getId() + extensionOf(job.getResultKey());
        String url = fileStorageService.generatePresignedDownloadUrl(job.getResultKey(), filename,
                storageProperties.download().getTtl());
        return new DownloadUrlResponse(url, offset(LocalDateTime.now(clock).plus(storageProperties.download().getTtl())));
    }

    /** 삭제 요청 — 목록·ZIP·결과 링크에서 즉시 빠지고 결과·썸네일은 커밋 후 지운다 */
    public void delete(String actorPublicId, String eventPublicId, Long photoId) {
        Long actorId = userId(actorPublicId);
        Event event = policy.requirePhoto(actorId, eventPublicId, PhotoPermission.DELETE_PHOTOS);
        ComposeJob job = photoOf(event, photoId);
        job.markDeleted(LocalDateTime.now(clock));
        exportService.invalidateContaining(job.getId());   // 이 사진을 담은 묶음은 더는 받을 수 없다
        if (job.getResultKey() != null) {
            s3Deleter.deleteAfterCommit(List.of(job.getResultKey(), ComposeService.thumbnailKeyFor(job)));
        }
        auditLogService.record(actorId, event.getWorkspaceId(), event.getId(), "PHOTO_DELETED", "PHOTO",
                job.getId().toString(), null);
    }

    private ComposeJob photoOf(Event event, Long photoId) {
        return composeJobRepository.findById(photoId)
                .filter(job -> event.getId().equals(job.getEventId()) && !job.isDeleted())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private PhotoDtos.Photo photo(ComposeJob job, ParticipantChannel channel) {
        String thumbnailUrl = job.getStatus() == ComposeStatus.DONE && job.getResultKey() != null
                ? fileStorageService.generatePresignedGetUrl(ComposeService.thumbnailKeyFor(job), storageProperties.download().getTtl())
                : null;
        PhotoDtos.Failure failure = job.getStatus() == ComposeStatus.FAILED ? failure(job.getFailureReason()) : null;
        return new PhotoDtos.Photo(job.getId(), job.getStatus(), offset(job.getCreatedAt()), channel,
                job.getFrameVersionId(), thumbnailUrl, failure);
    }

    // 실패 사유 문자열(내부 예외)을 화면용 코드로 접는다 — 원문은 내보내지 않는다
    static PhotoDtos.Failure failure(String reason) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (text.contains("nosuchkey") || text.contains("not found") || text.contains("missing")) {
            return new PhotoDtos.Failure("INPUT_MISSING", false);
        }
        if (text.contains("timeout") || text.contains("timed out") || text.contains("eventageexceeded")) {
            return new PhotoDtos.Failure("TIMEOUT", true);
        }
        return new PhotoDtos.Failure("COMPOSE_FAILED", true);
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? ".jpg" : key.substring(dot);
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

    // 불투명 커서 — (created_at, id) 를 base64url 로. 서버만 해석한다
    record Cursor(LocalDateTime createdAt, Long id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "잘못된 커서");
            }
        }
    }
}
