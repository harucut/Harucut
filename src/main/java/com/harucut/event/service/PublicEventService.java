package com.harucut.event.service;

import com.harucut.frame.cell.PhotoCell;
import com.harucut.frame.version.FrameSpecV2;
import java.util.List;
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
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.workspace.service.WorkspaceService;
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
    private final WorkspaceService workspaceService;
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

        // 긴급 회수된 발행본은 빠진다 — 다른 프레임으로 대체하지 않아 빈 목록일 수 있다(R5 F32)
        var frames = eventFrameRepository.findAllByExperienceVersionIdOrderBySortOrderAsc(version.getId()).stream()
                .filter(frame -> frameVersionRepository.findById(frame.getFrameVersionId()).filter(FrameVersion::revoked).isEmpty())
                .map(frame -> new PublicEventResponse.OfferedFrame(frame.getFrameVersionId(), frame.getLayoutType(),
                        frame.getSortOrder(), frame.isDefault(), previewUrl(frame.getFrameVersionId()), cells(frame.getFrameVersionId())))
                .toList();
        // 공간 OWNER 가 전부 정지면 닫힌 것으로 보여 준다 — 세션·회차·합성이 EVENT-006 으로 막히는 것을 화면이 미리 안다
        boolean captureOpen = event.captureOpen(LocalDateTime.now(clock))
                && !workspaceService.participationSuspended(event.getWorkspaceId());
        return new PublicEventResponse(event.getPublicId(), event.getName(), event.getStatus(),
                captureOpen, event.isByodEnabled(), event.isBoothEnabled(),
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

    // 자유 사진 칸 — 발행본 스냅샷 그대로. 고정 레이아웃이면 없다
    private List<PhotoCell> cells(Long frameVersionId) {
        return frameVersionRepository.findById(frameVersionId)
                .map(version -> objectMapper.readValue(version.getSpecJson(), FrameSpecV2.class).layout().cells())
                .map(cells -> cells.stream().map(cell -> new PhotoCell(cell.x(), cell.y(), cell.width(), cell.height(),
                        cell.rotation(), cell.shape(), cell.shotIndex())).toList())
                .orElse(null);
    }

    private static BusinessException notFound() {
        return new BusinessException(GlobalErrorCode.NOT_FOUND);
    }
}
