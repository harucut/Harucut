package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.media.dto.SharedMediaResponse;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.policy.MediaSubscriptionPolicy;
import com.harucut.media.repository.MediaShareRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 개인 사진 공유 링크(/s/&lt;token&gt;, 사용자 결정 2026-11-16). 링크는 그 사진이 주인 기록에 보이는 동안만 열린다 —
 * 사진을 지우거나, 보관 기간(요금제)이 지나거나, 주인이 탈퇴(요청)·정지되면 닫힌다. 판정을 저장하지 않고 열 때마다 하므로
 * 요금제를 올리거나 정지가 풀리면 같은 링크가 다시 열린다. 토큰은 결과 토큰처럼 Authorization 헤더로만 받는다(EventCredentialFilter).
 * 링크를 만드는 쪽(주인)은 UserMediaService.shareLink 다 — 사진 소유 확인을 같이 쓴다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class MediaShareService {

    private final UserMediaRepository userMediaRepository;
    private final MediaShareRepository mediaShareRepository;
    private final MediaSubscriptionPolicy mediaSubscriptionPolicy;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    /** 받는 사람이 연 링크 — 닫힌 이유는 가르지 않는다(MEDIA-002 하나). URL 은 짧게(기본 5분) 연다 */
    @Transactional(readOnly = true)
    public SharedMediaResponse open(MediaSharePrincipal principal) {
        UserMedia media = mediaShareRepository.findById(principal.mediaShareId())
                .flatMap(share -> userMediaRepository.findById(share.getMediaId()))
                .filter(this::visibleToOwner)
                .orElseThrow(() -> new BusinessException(MediaErrorCode.SHARE_CLOSED));
        Duration ttl = storageProperties.download().getTtl();
        return new SharedMediaResponse(media.getDisplayName(), media.getCreatedAt(),
                fileStorageService.generatePresignedGetUrl(media.getS3Key(), ttl),
                fileStorageService.generatePresignedDownloadUrl(media.getS3Key(), media.getDisplayName(), ttl));
    }

    // 주인 목록에 지금 보이는가 — 계정을 쓸 수 있고(탈퇴 요청·정지·탈퇴면 아니다) 보관 기간 안이다
    private boolean visibleToOwner(UserMedia media) {
        User owner = media.getUser();
        return owner.getUserStatus() == UserStatus.ACTIVE
                && mediaSubscriptionPolicy.isHistoryAccessible(owner.getId(), media.getCreatedAt());
    }
}
