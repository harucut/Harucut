package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.PageResponse;
import com.harucut.common.utils.OpaqueTokens;
import com.harucut.common.utils.PageRequests;
import com.harucut.media.dto.MediaShareLinkResponse;
import com.harucut.media.dto.UserMediaResponse;
import com.harucut.media.entity.MediaShare;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.policy.MediaSubscriptionPolicy;
import com.harucut.media.repository.MediaShareRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.media.util.DisplayNames;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service
@Transactional
@RequiredArgsConstructor
public class UserMediaService {

    private final UserRepository userRepository;
    private final UserMediaRepository userMediaRepository;
    private final FileStorageService fileStorageService;
    private final MediaSubscriptionPolicy mediaSubscriptionPolicy;
    private final S3Deleter s3Deleter;
    private final MediaShareRepository mediaShareRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<UserMediaResponse> getMyMedia(String publicId, int page, int size) {
        User user = getUser(publicId);
        Pageable pageable = PageRequests.of(page, size);
        LocalDateTime cutoff = mediaSubscriptionPolicy.resolveHistoryCutoff(user.getId());

        // 페이징이라 cutoff 필터가 쿼리 안에 있어야 전체 개수가 맞는다
        Page<UserMedia> mediaPage = cutoff == null
                ? userMediaRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)
                : userMediaRepository.findAllByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        user, cutoff, pageable);
        return PageResponse.from(mediaPage.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public String getDownloadUrl(String publicId, Long mediaId) {
        User user = getUser(publicId);
        UserMedia media = getOwnedMedia(user, mediaId);
        mediaSubscriptionPolicy.assertHistoryAccessible(user.getId(), media.getCreatedAt());

        return fileStorageService.generatePresignedDownloadUrl(media.getS3Key(), media.getDisplayName());
    }

    public UserMediaResponse updateDisplayName(String publicId, Long mediaId, String displayName) {
        User user = getUser(publicId);
        UserMedia media = getOwnedMedia(user, mediaId);
        mediaSubscriptionPolicy.assertHistoryAccessible(user.getId(), media.getCreatedAt());

        media.changeDisplayName(DisplayNames.resolve(displayName, media.getS3Key(), media.getCreatedAt()));
        return toResponse(media);
    }

    /** 사진 공유 링크의 토큰(MediaShareService) — 사진마다 하나라 몇 번을 불러도 같다. 남의 사진·없는 사진·보관 기간 지난 사진은 단건 조회와 같은 오류 */
    public MediaShareLinkResponse shareLink(String publicId, Long mediaId) {
        User user = getUser(publicId);
        UserMedia media = getOwnedMedia(user, mediaId);
        mediaSubscriptionPolicy.assertHistoryAccessible(user.getId(), media.getCreatedAt());
        MediaShare share = mediaShareRepository.findByMediaId(media.getId()).orElseGet(() -> createShare(media.getId()));
        return new MediaShareLinkResponse(share.getToken());
    }

    // 동시에 두 번 눌렀으면 늦은 쪽의 insert 는 0행이다 — 잠금 읽기로 먼저 들어간 쪽의 토큰을 받는다
    private MediaShare createShare(Long mediaId) {
        mediaShareRepository.insertIfAbsent(mediaId, OpaqueTokens.generate(), LocalDateTime.now(clock));
        return mediaShareRepository.lockByMediaId(mediaId)
                // 그사이 사진이 지워졌다 — insert ignore 가 FK 위반을 0행으로 넘겼다
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    public void deleteMedia(String publicId, Long mediaId) {
        User user = getUser(publicId);
        UserMedia media = getOwnedMedia(user, mediaId);
        // 기간 검사 없음 — 안 보이는 미디어도 지우는 건 가능해야 한다 (프레임 삭제와 같은 원칙)

        // List.of가 아니라 Arrays.asList인 이유 — 썸네일 없는 옛 행의 null을 List.of는
        // 거부한다(NPE). null은 S3Deleter의 관리 key 필터가 거른다
        // 고정 key 라 이미 돌고 있던 합성이 나중에 다시 쓸 수 있다 — 지금 한 번, 그 기간 뒤 한 번 더(R5 L498)
        s3Deleter.deleteAfterCommit(Arrays.asList(media.getS3Key(), media.getThumbnailKey()), ComposeService.LATE_WRITE_WINDOW);
        // 공유 링크도 닫힌다 — 그 행이 사진을 FK 로 참조하니 먼저 지운다
        mediaShareRepository.deleteByMediaId(media.getId());
        userMediaRepository.delete(media);
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    // 소유자 조건이 쿼리에 있어 남의 것과 없는 것이 같은 404로 나간다
    private UserMedia getOwnedMedia(User user, Long mediaId) {
        return userMediaRepository.findByIdAndUser(mediaId, user)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private UserMediaResponse toResponse(UserMedia media) {
        // 같은 파일에 URL이 둘인 이유 — viewUrl은 <img>용 plain GET,
        // downloadUrl은 attachment disposition이라 브라우저가 저장 대화상자를 띄운다.
        // thumbnailUrl은 축소본이 있을 때만 — null이면 응답에서 필드째 생략된다
        String thumbnailUrl = media.getThumbnailKey() == null ? null
                : fileStorageService.generatePresignedGetUrl(media.getThumbnailKey());
        String viewUrl = fileStorageService.generatePresignedGetUrl(media.getS3Key());
        String downloadUrl = fileStorageService
                .generatePresignedDownloadUrl(media.getS3Key(), media.getDisplayName());
        return UserMediaResponse.of(media, thumbnailUrl, viewUrl, downloadUrl);
    }
}
