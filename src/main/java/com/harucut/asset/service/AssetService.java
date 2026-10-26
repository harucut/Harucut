package com.harucut.asset.service;

import com.harucut.asset.dto.AssetResponse;
import com.harucut.asset.dto.UploadIntentRequest;
import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.enums.AssetStatus;
import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.PresignedUploadRequest;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.enums.ContentType;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 자산 업로드 의도 → 브라우저 PUT → 완료 검증 (ADR-0006 §2).
 *
 * <p>complete 는 한 트랜잭션으로 묶지 않는다. S3 HEAD·다운로드·디코드는 수 초 걸릴 수 있어서
 * 그동안 DB 커넥션을 붙잡지 않도록, 읽기와 판정 저장만 각각 짧은 트랜잭션에서 한다.
 */
@Service
public class AssetService {

    // 이 경로에서 받는 용도. CAPTURE_INPUT 은 참가자 경로(P2A), PAGE_THEME_ASSET 은 행사 테마(P2B)
    private static final Set<AssetPurpose> CREATOR_PURPOSES =
            Set.of(AssetPurpose.FRAME_ASSET, AssetPurpose.TEXT_RENDER, AssetPurpose.PROFILE);
    private static final Set<WorkspaceRole> UPLOAD_ROLES =
            Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.DESIGNER);

    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final FileStorageService fileStorageService;
    private final StorageProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;
    private final Semaphore verifySlots;
    private final AssetImageInspector inspector;

    public AssetService(AssetRepository assetRepository, UserRepository userRepository,
                        WorkspaceService workspaceService, FileStorageService fileStorageService,
                        StorageProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
        this.fileStorageService = fileStorageService;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.verifySlots = new Semaphore(properties.asset().verifyConcurrency());
        this.inspector = new AssetImageInspector(properties.asset().maxPixels());
    }

    public UploadIntentResponse createIntent(String publicId, UploadIntentRequest request) {
        if (!CREATOR_PURPOSES.contains(request.purpose())) {
            throw new BusinessException(AssetErrorCode.PURPOSE_NOT_ALLOWED);
        }
        if (!request.purpose().accepts(request.contentType())) {
            throw new BusinessException(GlobalErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    request.purpose() + " 은 " + request.contentType() + " 를 받지 않습니다.");
        }
        String extension = request.contentType().validateExtension(extensionOf(request.filename()));

        Asset asset = transactions.execute(status -> {
            User user = getUser(publicId);
            Workspace workspace = request.workspaceId() == null
                    ? workspaceService.personalWorkspaceOf(user.getId())
                    : workspaceService.requireRole(request.workspaceId(), user.getId(), UPLOAD_ROLES);
            String assetPublicId = Asset.newPublicId();
            // 사용자 폴더 아래 — 기존 소유권 검사(S3Keys.assertOwnedBy)와 Lambda IAM(uploads/*)이 그대로 맞는다
            String key = S3Keys.userRoot(user.getPublicId()) + "assets/" + assetPublicId + "." + extension;
            return assetRepository.save(Asset.pendingUpload(assetPublicId, workspace.getId(), request.purpose(),
                    key, request.contentType().getMimeType(), request.fileSize(), user.getId()));
        });

        PresignedPut put = fileStorageService.presignPut(asset.getS3Key(), asset.getContentType(),
                asset.getDeclaredSize(), properties.upload().putTtl(), properties.upload().conditionalPut());
        return new UploadIntentResponse(asset.getPublicId(), put.url(), "PUT", put.headers(), put.expiresAt());
    }

    /**
     * 업로드 완료 확인. 멱등이다 — 이미 판정이 끝났으면 그 결과를 그대로 돌려준다.
     * 객체가 아직 없으면 409 ASSET-001 이고 상태를 바꾸지 않는다(올리는 중일 수 있다).
     */
    public CompleteResult complete(String publicId, String assetPublicId) {
        Asset asset = readOnly.execute(status -> findAccessible(publicId, assetPublicId));
        if (asset.getStatus() != AssetStatus.PENDING_UPLOAD) {
            return result(asset);
        }

        StoredObject stored = fileStorageService.head(asset.getS3Key())
                .orElseThrow(() -> new BusinessException(AssetErrorCode.OBJECT_NOT_UPLOADED));

        Verdict verdict = judge(asset, stored);
        Asset judged = transactions.execute(status -> {
            Asset current = assetRepository.findById(asset.getId()).orElseThrow();
            if (current.getStatus() != AssetStatus.PENDING_UPLOAD) {
                return current;   // 동시에 온 다른 complete 가 먼저 판정했다
            }
            if (verdict.rejection() != null) {
                current.reject(verdict.rejection().getCode());
            } else {
                current.verify(stored.size(), verdict.width(), verdict.height(), LocalDateTime.now(clock));
            }
            return current;
        });
        return result(judged);
    }

    public AssetResponse get(String publicId, String assetPublicId) {
        Asset asset = readOnly.execute(status -> findAccessible(publicId, assetPublicId));
        return response(asset);
    }

    private Verdict judge(Asset asset, StoredObject stored) {
        if (stored.size() != asset.getDeclaredSize() || stored.size() > PresignedUploadRequest.MAX_FILE_SIZE) {
            return Verdict.rejected(AssetErrorCode.SIZE_MISMATCH);
        }
        acquireVerifySlot();
        try {
            byte[] bytes = fileStorageService.downloadBytes(asset.getS3Key());
            AssetImageInspector.Inspection inspection = inspector.inspect(bytes, contentTypeOf(asset));
            return Verdict.verified(inspection.width(), inspection.height());
        } catch (AssetImageInspector.Rejected rejected) {
            return Verdict.rejected(rejected.code());
        } finally {
            verifySlots.release();
        }
    }

    private void acquireVerifySlot() {
        try {
            if (!verifySlots.tryAcquire(properties.asset().verifyWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BusinessException(AssetErrorCode.VERIFY_BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(AssetErrorCode.VERIFY_BUSY);
        }
    }

    // 업로더 본인이거나 그 공간의 멤버만 본다. 아니면 존재도 알리지 않는다(404)
    private Asset findAccessible(String publicId, String assetPublicId) {
        User user = getUser(publicId);
        return assetRepository.findByPublicId(assetPublicId)
                .filter(asset -> user.getId().equals(asset.getUploadedByUserId())
                        || workspaceService.isMember(asset.getWorkspaceId(), user.getId()))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private CompleteResult result(Asset asset) {
        Optional<AssetErrorCode> rejection = Optional.ofNullable(asset.getRejectReason())
                .map(AssetErrorCode::fromCode);
        return new CompleteResult(response(asset), rejection.orElse(null));
    }

    private AssetResponse response(Asset asset) {
        if (!asset.isVerified()) {
            return AssetResponse.of(asset, null, null);
        }
        Instant expiresAt = clock.instant().plus(properties.download().getTtl());
        return AssetResponse.of(asset,
                fileStorageService.generatePresignedGetUrl(asset.getS3Key(), properties.download().getTtl()),
                expiresAt);
    }

    private static ContentType contentTypeOf(Asset asset) {
        for (ContentType type : ContentType.values()) {
            if (type.getMimeType().equals(asset.getContentType())) {
                return type;
            }
        }
        throw new IllegalStateException("알 수 없는 MIME: " + asset.getContentType());
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot == -1 || dot == filename.length() - 1) ? "" : filename.substring(dot + 1);
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    /** rejection 이 있으면 컨트롤러가 그 코드의 HTTP 상태(422)로, 본문 data 에 자산을 실어 내보낸다 */
    public record CompleteResult(AssetResponse asset, AssetErrorCode rejection) {
    }

    private record Verdict(int width, int height, AssetErrorCode rejection) {
        static Verdict verified(int width, int height) {
            return new Verdict(width, height, null);
        }

        static Verdict rejected(AssetErrorCode code) {
            return new Verdict(0, 0, code);
        }
    }
}
