package com.harucut.event.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.event.dto.PublicEventResponse;
import com.harucut.event.entity.Event;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.event.repository.EventFrameRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 공개 행사 정보 — 게시된 경험 버전만 내준다(ADR-0007 §5-1). 게시 전·보관 종료는 404 라 존재도 알리지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicEventService {

    private final EventRepository eventRepository;
    private final EventExperienceVersionRepository experienceVersionRepository;
    private final EventFrameRepository eventFrameRepository;
    private final FrameVersionRepository frameVersionRepository;
    private final FrameRepository frameRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final ThemeResolver themeResolver;
    private final Clock clock;

    public PublicEventResponse getBySlug(String slug) {
        return view(eventRepository.findBySlug(slug).orElseThrow(PublicEventService::notFound));
    }

    public PublicEventResponse view(Event event) {
        if (event.getCurrentExperienceVersionId() == null
                || event.getStatus() == EventStatus.DRAFT || event.getStatus() == EventStatus.ARCHIVED) {
            throw notFound();
        }
        EventExperienceVersion version = experienceVersionRepository.findById(event.getCurrentExperienceVersionId())
                .orElseThrow(PublicEventService::notFound);
        EventExperienceDocument document = objectMapper.readValue(version.getSettingsJson(), EventExperienceDocument.class);

        var frames = eventFrameRepository.findAllByExperienceVersionIdOrderBySortOrderAsc(version.getId()).stream()
                .map(frame -> new PublicEventResponse.OfferedFrame(frame.getFrameVersionId(), frame.getLayoutType(),
                        frame.getSortOrder(), frame.isDefault(), previewUrl(frame.getFrameVersionId())))
                .toList();
        return new PublicEventResponse(event.getPublicId(), event.getName(), event.getStatus(),
                event.captureOpen(LocalDateTime.now(clock)), event.isByodEnabled(), event.isBoothEnabled(),
                version.getVersionNo(), document.allowedLayouts(), frames, document.consentItems(), themeResolver.resolve(document.theme()),
                document.offeredFreeText().map(PublicEventResponse.OfferedFreeText::of).orElse(null));
    }

    // 발행본의 원본 프레임 미리보기 — 프레임이 나중에 바뀌어도 발행본 자체는 그대로다(미리보기는 참고용)
    private String previewUrl(Long frameVersionId) {
        return frameVersionRepository.findById(frameVersionId)
                .flatMap(version -> frameRepository.findById(version.getFrameId()))
                .map(frame -> fileStorageService.generatePresignedGetUrl(frame.getPreviewKey(), storageProperties.download().getTtl()))
                .orElse(null);
    }

    private static BusinessException notFound() {
        return new BusinessException(GlobalErrorCode.NOT_FOUND);
    }
}
