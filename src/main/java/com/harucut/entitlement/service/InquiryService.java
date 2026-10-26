package com.harucut.entitlement.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.RateLimiter;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Inquiry;
import com.harucut.entitlement.repository.InquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** 행사·기업 문의 (ADR-0008 §6-1) — 로그인 없이 받고 IP 분당 5회로 막는다. 처리는 플랫폼 ADMIN */
@Service
@Transactional
@RequiredArgsConstructor
public class InquiryService {

    private static final int PER_MINUTE = 5;

    private final InquiryRepository inquiryRepository;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public EntitlementDtos.InquiryCreated create(EntitlementDtos.InquiryRequest request, String clientIp) {
        if (!rateLimiter.tryAcquire("inquiry", clientIp, PER_MINUTE, Duration.ofMinutes(1))) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
        String channels = request.channels().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        Inquiry inquiry = inquiryRepository.save(new Inquiry(request.contactName().strip(), request.email().strip(),
                blankToNull(request.phone()), request.organizerType(), channels, request.expectedDate(),
                request.expectedCount(), request.message().strip()));
        return new EntitlementDtos.InquiryCreated(inquiry.getPublicId());
    }

    @Transactional(readOnly = true)
    public List<EntitlementDtos.Inquiry> list() {
        return inquiryRepository.findAllByOrderByIdDesc(PageRequest.of(0, 200)).stream().map(this::view).toList();
    }

    public EntitlementDtos.Inquiry update(String inquiryPublicId, EntitlementDtos.InquiryUpdateRequest request) {
        Inquiry inquiry = inquiryRepository.findByPublicId(inquiryPublicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        inquiry.update(request.status(), request.assigneeNote());
        return view(inquiry);
    }

    private EntitlementDtos.Inquiry view(Inquiry i) {
        return new EntitlementDtos.Inquiry(i.getPublicId(), i.getContactName(), i.getEmail(), i.getPhone(),
                i.getOrganizerType(), i.getChannels(), i.getExpectedDate(), i.getExpectedCount(), i.getMessage(),
                i.getStatus(), i.getAssigneeNote(), i.getCreatedAt() == null ? null : i.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
