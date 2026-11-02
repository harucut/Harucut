package com.harucut.result.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.event.dto.PageTheme;
import com.harucut.event.dto.ResolvedPageTheme;
import com.harucut.event.service.ThemeResolver;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.result.dto.ResultStatusResponse;
import com.harucut.result.dto.ResultTokenResponse;
import com.harucut.result.entity.ResultAccess;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.result.repository.ResultAccessRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 개인 결과 링크(/r#<token>)의 서버 쪽 (ADR-0007 §8). 토큰은 경로·쿼리가 아니라 Authorization 헤더로만 받는다.
 * 첫 조회로 소모하지 않는다. 다운로드할 때마다 보관 기한·상태를 다시 보고 짧은 URL 을 준다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ResultService {

    private static final int DOWNLOAD_NAME_LENGTH = 40;
    private static final DateTimeFormatter DOWNLOAD_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ResultAccessRepository resultAccessRepository;
    private final ComposeJobRepository composeJobRepository;
    private final EventRepository eventRepository;
    private final EventExperienceVersionRepository experienceVersionRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final ThemeResolver themeResolver;
    private final Clock clock;

    /** 행사 작업의 결과 토큰 발급. 만료는 작업의 보관 약정을 따른다 */
    public ResultTokenResponse issue(ComposeJob job, Event event) {
        String token = OpaqueTokens.generate();
        resultAccessRepository.save(new ResultAccess(OpaqueTokens.hash(token), job.getId()));
        return new ResultTokenResponse(token, offset(retentionEnd(job, event)));
    }

    @Transactional(readOnly = true)
    public ResultStatusResponse status(ResultPrincipal principal) {
        ResultAccess access = access(principal);
        ComposeJob job = job(access.getComposeJobId());
        Event event = event(job);
        // 사진함에서 삭제된 결과도 참가자에게는 「만료」로 보인다 — 삭제 사실을 따로 알리지 않는다(ADR-0008 §3)
        LocalDateTime retainUntil = retentionEnd(job, event);
        boolean expired = !LocalDateTime.now(clock).isBefore(retainUntil) || job.isDeleted();
        String status = expired ? "EXPIRED" : job.getStatus().name();
        boolean ready = !expired && job.getStatus() == ComposeStatus.DONE;
        String thumbnailUrl = ready ? shortGetUrl(ComposeService.thumbnailKeyFor(job)) : null;
        // 썸네일은 긴 변 512px 라 세로 4컷이면 폭이 170px 이다 — 처음 보는 화면은 원본으로 그린다
        String imageUrl = ready && job.getResultKey() != null ? shortGetUrl(job.getResultKey()) : null;
        return new ResultStatusResponse(status, event.getName(), thumbnailUrl, imageUrl, offset(retainUntil), theme(job));
    }

    /** 재방문 목록의 미리보기 — 끝난 작업만. 삭제·보관 종료된 작업은 호출자가 이미 뺐다 */
    public String thumbnailUrl(ComposeJob job) {
        return job.getStatus() == ComposeStatus.DONE ? shortGetUrl(ComposeService.thumbnailKeyFor(job)) : null;
    }

    private String shortGetUrl(String key) {
        return fileStorageService.generatePresignedGetUrl(key, storageProperties.download().getTtl());
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse download(ResultPrincipal principal) {
        ComposeJob job = job(access(principal).getComposeJobId());
        Event event = event(job);
        if (!LocalDateTime.now(clock).isBefore(retentionEnd(job, event)) || job.isDeleted()) {
            throw new BusinessException(ResultErrorCode.EXPIRED);
        }
        return downloadUrl(job, event);
    }

    /** 참가자 재방문 다운로드(BYOD) — 결과 토큰 없이 세션으로. 같은 보관·준비 검사 */
    @Transactional(readOnly = true)
    public DownloadUrlResponse downloadForParticipant(ComposeJob job) {
        Event event = event(job);
        if (job.isDeleted() || !LocalDateTime.now(clock).isBefore(retentionEnd(job, event))) {
            throw new BusinessException(ResultErrorCode.EXPIRED);
        }
        return downloadUrl(job, event);
    }

    // attachment 로 서명한다 — 그냥 GET URL 이면 브라우저가 결과 이미지로 페이지를 옮겨 행사 화면을 잃는다
    private DownloadUrlResponse downloadUrl(ComposeJob job, Event event) {
        if (job.getStatus() != ComposeStatus.DONE || job.getResultKey() == null) {
            throw new BusinessException(ResultErrorCode.NOT_READY);
        }
        LocalDateTime takenAt = job.getCreatedAt() != null ? job.getCreatedAt() : LocalDateTime.now(clock);
        String url = fileStorageService.generatePresignedDownloadUrl(job.getResultKey(),
                downloadName(event.getName(), takenAt, job.getResultKey()), storageProperties.download().getTtl());
        return new DownloadUrlResponse(url, offset(LocalDateTime.now(clock).plus(storageProperties.download().getTtl())));
    }

    // 「행사 이름 일부-찍은 시각.jpg」. 결과 링크는 여러 사람에게 전달되므로 내부 작업 번호는 싣지 않는다.
    // 행사 이름은 주최자 입력이다 — 보이지 않는 제어·서식 문자(방향 바꾸기 등)를 빼고 URL 이 길어지지 않게 자른다
    static String downloadName(String eventName, LocalDateTime takenAt, String resultKey) {
        String name = eventName.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        String part = name.codePointCount(0, name.length()) <= DOWNLOAD_NAME_LENGTH ? name
                : name.substring(0, name.offsetByCodePoints(0, DOWNLOAD_NAME_LENGTH)).strip();
        return (part.isEmpty() ? "harucut" : part) + "-" + takenAt.format(DOWNLOAD_TIME) + S3Keys.extensionOf(resultKey);
    }

    // 작업에 굳은 약정 — 토큰마다 따로 두지 않는다. 약정을 늘리면 이전에 발급한 링크도 함께 늘어난다(R5 L330, O31)
    private LocalDateTime retentionEnd(ComposeJob job, Event event) {
        if (job.getRetainUntil() != null) {
            return job.getRetainUntil();
        }
        LocalDateTime base = job.getCreatedAt() != null ? job.getCreatedAt() : LocalDateTime.now(clock);
        return base.plusDays(event.getResultRetentionDays());
    }

    // 그 job 에 고정된 경험 버전의 테마 — 결과 화면은 찍을 때의 디자인으로 그린다
    private ResolvedPageTheme theme(ComposeJob job) {
        PageTheme theme = job.getExperienceVersionId() == null ? null
                : experienceVersionRepository.findById(job.getExperienceVersionId())
                .map(version -> objectMapper.readValue(version.getSettingsJson(), EventExperienceDocument.class).theme())
                .orElse(null);
        return themeResolver.resolve(theme);
    }

    private ResultAccess access(ResultPrincipal principal) {
        return resultAccessRepository.findById(principal.resultAccessId())
                .filter(access -> !access.revoked())
                .orElseThrow(() -> new BusinessException(ResultErrorCode.TOKEN_INVALID));
    }

    private ComposeJob job(Long jobId) {
        return composeJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ResultErrorCode.TOKEN_INVALID));
    }

    private Event event(ComposeJob job) {
        if (job.getEventId() == null) {
            throw new BusinessException(GlobalErrorCode.NOT_FOUND);
        }
        return eventRepository.findById(job.getEventId())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
