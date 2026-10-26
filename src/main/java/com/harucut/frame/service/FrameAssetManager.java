package com.harucut.frame.service;

import com.harucut.frame.enums.ComponentType;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

// 프레임이 참조하는 S3 자산의 경계 담당: 저장 전 key 정규화, 응답 시 presign, 커밋 후 삭제 예약.
// 관리 대상(uploads/ 아래)만 손댄다 — PHOTO 외 컴포넌트(STICKER=정적 경로, TEXT=본문 텍스트)와
// 외부 URL은 그대로 통과시킨다.
@Component
@RequiredArgsConstructor
public class FrameAssetManager {

    private final FileStorageService fileStorageService;
    private final S3Deleter s3Deleter;

    // 저장 직전: PHOTO의 source만 순수 key로. presigned URL이 들어와도 key만 남긴다.
    //
    // ownerPublicId는 "이 key가 누구 것이어야 하는가"다. null이면 검사를 건너뛴다 —
    // 관리자가 만드는 시스템 프레임 전용이다(ROLE_ADMIN이 이미 신뢰 경계라 소유자 개념이 없다).
    // 사용자 경로에서는 절대 null을 넘기지 말 것
    public String normalizeSource(ComponentType type, String source, String ownerPublicId) {
        if (type == ComponentType.STICKER) {
            // 스티커는 정적 경로가 기본이지만 uploads/ key 도 들어온다 — 합성은 그 key 를 그대로
            // 읽으므로(ComposeSpecAssembler) 남의 key 를 막지 않으면 남의 파일로 합성할 수 있다.
            // 검사만 하고 값은 바꾸지 않는다: 정규화하면 응답에 presign 없는 key 가 나가
            // 지금 화면이 보던 값이 달라진다(스티커의 주소 변환은 별도 작업)
            if (ownerPublicId != null) {
                S3Keys.assertOwnedBy(source, ownerPublicId);
            }
            return source;
        }
        if (type != ComponentType.PHOTO) {
            return source;
        }
        return normalizeImageKey(source, ownerPublicId);
    }

    // 배경 IMAGE key와 previewKey용 — 항상 이미지 의미라 타입 인자가 필요 없다.
    // 검사를 정규화 뒤에 하는 순서가 중요하다: URL로 감싼 남의 key도 정규화하면 순수 key로 드러난다
    public String normalizeImageKey(String pathOrKey, String ownerPublicId) {
        String normalized = S3Keys.normalizeManagedKey(pathOrKey);
        if (ownerPublicId != null) {
            S3Keys.assertOwnedBy(normalized, ownerPublicId);
        }
        return normalized;
    }

    // 응답 조립: PHOTO·STICKER 이고 관리 대상이면 presigned GET URL, 아니면 원본 그대로(정적 스티커 경로·TEXT 본문).
    // 재저장용 순수 key 는 응답의 key 필드가 따로 들고 간다
    public String resolveSource(ComponentType type, String source) {
        if (type == ComponentType.TEXT) {
            return source;
        }
        return presignIfManaged(source);
    }

    public String resolveImageSource(String pathOrKey) {
        return presignIfManaged(pathOrKey);
    }

    // 커밋 후 삭제 예약 — 거르기와 발행은 storage 공용 부품이 한다 (media와 공유)
    public void deleteAfterCommit(Collection<String> keys) {
        s3Deleter.deleteAfterCommit(keys);
    }

    private String presignIfManaged(String source) {
        String normalized = S3Keys.normalizeManagedKey(source);
        return S3Keys.isManagedKey(normalized)
                ? fileStorageService.generatePresignedGetUrl(normalized)
                : source;
    }
}
