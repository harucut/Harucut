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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 개인 결과 링크(/r#<token>)의 서버 쪽 (ADR-0007 §8). 토큰은 경로·쿼리가 아니라 Authorization 헤더로만 받는다.
 * 첫 조회로 소모하지 않는다. 다운로드할 때마다 보관 기한·상태를 다시 보고 짧은 URL 을 준다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ResultService {

    private final ResultAccessRepository resultAccessRepository;
    private final ComposeJobRepository composeJobRepository;
    private final EventRepository eventRepository;
    private final EventExperienceVersionRepository experienceVersionRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final ThemeResolver themeResolver;
    private final Clock clock;

    /** 행사 작업의 결과 토큰 발급. 만료는 작업 접수 시각 + 행사 보관 일수다 */
    public ResultTokenResponse issue(ComposeJob job, Event event) {
        String token = OpaqueTokens.generate();
        LocalDateTime expiresAt = retentionEnd(job, event);
        resultAccessRepository.save(new ResultAccess(OpaqueTokens.hash(token), job.getId(), expiresAt));
        return new ResultTokenResponse(token, offset(expiresAt));
    }

    @Transactional(readOnly = true)
    public ResultStatusResponse status(ResultPrincipal principal) {
        ResultAccess access = access(principal);
        ComposeJob job = job(access.getComposeJobId());
        Event event = event(job);
        // 사진함에서 삭제된 결과도 참가자에게는 「만료」로 보인다 — 삭제 사실을 따로 알리지 않는다(ADR-0008 §3)
        boolean expired = access.expiredAt(LocalDateTime.now(clock)) || job.isDeleted();
        String status = expired ? "EXPIRED" : job.getStatus().name();
        String thumbnailUrl = !expired && job.getStatus() == ComposeStatus.DONE
                ? fileStorageService.generatePresignedGetUrl(ComposeService.thumbnailKeyFor(job), storageProperties.download().getTtl())
                : null;
        return new ResultStatusResponse(status, event.getName(), thumbnailUrl, offset(access.getExpiresAt()), theme(job));
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse download(ResultPrincipal principal) {
        ResultAccess access = access(principal);
        ComposeJob job = job(access.getComposeJobId());
        if (access.expiredAt(LocalDateTime.now(clock)) || job.isDeleted()) {
            throw new BusinessException(ResultErrorCode.EXPIRED);
        }
        return downloadUrl(job);
    }

    /** 참가자 재방문 다운로드(BYOD) — 결과 토큰 없이 세션으로. 같은 보관·준비 검사 */
    @Transactional(readOnly = true)
    public DownloadUrlResponse downloadForParticipant(ComposeJob job) {
        if (job.isDeleted() || !LocalDateTime.now(clock).isBefore(retentionEnd(job, event(job)))) {
            throw new BusinessException(ResultErrorCode.EXPIRED);
        }
        return downloadUrl(job);
    }

    private DownloadUrlResponse downloadUrl(ComposeJob job) {
        if (job.getStatus() != ComposeStatus.DONE || job.getResultKey() == null) {
            throw new BusinessException(ResultErrorCode.NOT_READY);
        }
        String url = fileStorageService.generatePresignedGetUrl(job.getResultKey(), storageProperties.download().getTtl());
        return new DownloadUrlResponse(url, offset(LocalDateTime.now(clock).plus(storageProperties.download().getTtl())));
    }

    private LocalDateTime retentionEnd(ComposeJob job, Event event) {
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
