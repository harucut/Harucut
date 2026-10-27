package com.harucut.participant.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.RateLimiter;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.frame.cell.FreeCellsGate;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.frame.version.FrameSpecComposer;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.entity.CaptureRun;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.result.dto.ResultTokenResponse;
import com.harucut.result.service.ResultService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 행사 합성 접수 (ADR-0007 §5-8). 기존 합성 파이프라인(ComposeService.accept → Lambda)에 그대로 싣는다.
 *
 * <p>순서: 같은 scope·키의 기존 작업이 있으면 지문을 대조해 돌려준다(새 검사·새 귀속 없이 — 이미 성공해 원본이
 * 정리된 정상 재요청도 성공해야 한다). 새 작업일 때만 세션·회차·행사·프레임·입력을 검사하고, 입력 귀속·작업·결과 토큰을
 * 한 트랜잭션에 저장한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ParticipantComposeService {

    private final ParticipantService participantService;
    private final ComposeJobRepository composeJobRepository;
    private final AssetRepository assetRepository;
    private final FrameVersionRepository frameVersionRepository;
    private final FrameSpecComposer frameSpecComposer;
    private final FreeCellsGate freeCellsGate;
    private final ComposeService composeService;
    private final ResultService resultService;
    private final EntitlementService entitlementService;
    private final ParticipantFreeTextService freeTextService;
    private final FileStorageService fileStorageService;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    /** 자유 문구 미리보기 — 참가자당 분당 상한(GEN-061). 렌더가 CPU 를 쓰므로 공개 요청 상한과 따로 둔다 */
    static final int PREVIEWS_PER_MINUTE = 30;

    public ParticipantDtos.ComposeAccepted submit(ParticipantPrincipal principal, String eventPublicId,
                                                  ParticipantDtos.ComposeRequest request) {
        Event event = participantService.requireOwnEvent(principal, eventPublicId);

        Optional<ComposeJob> existing = composeJobRepository.findByIdempotencyScopeAndIdempotencyKey(
                ComposeJob.participantScope(principal.participantId()), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), event, request);
        }

        participantService.requireActive(principal);
        CaptureRun run = participantService.activeRun(principal, request.captureRunId());
        // 보관 종료와 같은 행사 행 잠금으로 줄을 선다 — 검사를 통과한 접수가 커밋 전에 보관 종료되지 않게
        participantService.requireCaptureOpenLocked(event);
        if (!participantService.offers(run.getExperienceVersionId(), request.frameVersionId())) {
            throw new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED);
        }
        FrameVersion version = frameVersionRepository.findById(request.frameVersionId())
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED));
        freeCellsGate.requireRenderable(version);   // 자유 사진 칸 — 렌더 스위치 전에는 409 FRAME-008
        List<Asset> inputs = inputs(principal, run, request.sourceAssetIds());

        String storageRoot = S3Keys.UPLOAD_ROOT + "events/" + event.getPublicId() + "/runs/" + run.getPublicId() + "/";
        ComposeSpec spec;
        List<Long> bound = new ArrayList<>(inputs.stream().map(Asset::getId).toList());
        if (request.freeText() == null) {
            spec = frameSpecComposer.compose(version);
        } else {
            // 자유 문구 — 서버가 구운 PNG 를 이 작업 폴더에 올리고 맨 위 레이어로 얹는다(R5 D06). 문구 스냅샷은 자산 행에 남는다
            ParticipantFreeTextService.Prepared text = freeTextService.prepare(run.getExperienceVersionId(), version, request.freeText());
            Asset rendered = uploadTextRender(text, storageRoot, event, principal, run);
            bound.add(rendered.getId());
            spec = withTopLayer(text, rendered.getS3Key());
        }

        ComposeJob job = composeService.accept(ComposeJob.forParticipant(principal.participantId(), storageRoot,
                version.getFrameId(), request.idempotencyKey(), inputs.stream().map(Asset::getS3Key).toList(),
                spec, event.getId(), run.getId(), run.getExperienceVersionId(), version.getId()));
        // 입력은 한 작업에만 — 동시에 다른 작업이 같은 입력을 묶었으면 여기서 진다(트랜잭션 전체가 롤백된다)
        if (assetRepository.bindToJob(bound, job.getId()) != bound.size()) {
            throw new BusinessException(ParticipantErrorCode.INPUT_NOT_USABLE, "다른 작업에 이미 묶인 입력이 있다");
        }
        // 사용량 예약 — 작업 저장과 같은 트랜잭션. 한도가 없으면 ENT-002 로 작업·입력 귀속까지 함께 롤백된다
        entitlementService.reserve(job.getId(), event);
        return accepted(job, resultService.issue(job, event));
    }

    // 정확히 동시에 온 같은 키의 패자 — 승자를 새 트랜잭션에서 다시 읽는다(개인 합성과 같은 처리).
    // 승자가 없으면 null — 다른 제약 위반이라 호출자가 원래 예외를 던진다
    public ParticipantDtos.ComposeAccepted replayAfterConflict(ParticipantPrincipal principal, String eventPublicId,
                                                               ParticipantDtos.ComposeRequest request) {
        Event event = participantService.requireOwnEvent(principal, eventPublicId);
        return composeJobRepository.findByIdempotencyScopeAndIdempotencyKey(
                        ComposeJob.participantScope(principal.participantId()), request.idempotencyKey())
                .map(job -> replay(job, event, request))
                .orElse(null);
    }

    // 같은 키 = 같은 요청. 프레임·입력(칸 순서까지)이 같으면 기존 작업 + 새 결과 토큰 —
    // 토큰 원문을 저장하지 않으므로 이전 것을 다시 줄 수 없다. 이전 토큰도 계속 유효하다
    private ParticipantDtos.ComposeAccepted replay(ComposeJob job, Event event, ParticipantDtos.ComposeRequest request) {
        Map<String, String> keyById = assetRepository.findAllByPublicIdIn(request.sourceAssetIds()).stream()
                .collect(Collectors.toMap(Asset::getPublicId, Asset::getS3Key));
        List<String> requestedKeys = request.sourceAssetIds().stream().map(keyById::get).toList();
        if (!request.frameVersionId().equals(job.getFrameVersionId()) || !job.sourceKeys().equals(requestedKeys)
                || !freeTextService.matches(assetRepository.findServerTextRenderByJob(job.getId()), request.freeText())) {
            throw new BusinessException(MediaErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return accepted(job, resultService.issue(job, event));
    }

    // 칸 순서대로 4개 — 이 회차·이 참가자의 VERIFIED 촬영 입력이고 아직 어느 작업에도 묶이지 않았어야 한다
    private List<Asset> inputs(ParticipantPrincipal principal, CaptureRun run, List<String> assetIds) {
        if (assetIds.stream().distinct().count() != assetIds.size()) {
            throw new BusinessException(ParticipantErrorCode.INPUT_NOT_USABLE, "같은 입력을 두 칸에 넣었다");
        }
        Map<String, Asset> found = assetRepository.findAllByPublicIdIn(assetIds).stream()
                .collect(Collectors.toMap(Asset::getPublicId, Function.identity()));
        return assetIds.stream().map(id -> {
            Asset asset = found.get(id);
            if (asset == null || asset.getPurpose() != AssetPurpose.CAPTURE_INPUT || !asset.isVerified()
                    || !principal.participantId().equals(asset.getParticipantId())
                    || !run.getId().equals(asset.getCaptureRunId())
                    || asset.getBoundComposeJobId() != null) {
                throw new BusinessException(ParticipantErrorCode.INPUT_NOT_USABLE, "쓸 수 없는 입력: " + id);
            }
            return asset;
        }).toList();
    }

    /** 자유 문구 미리보기 — 합성과 같은 서버 렌더. 상태를 남기지 않는다(올리지도, 기록하지도 않는다) */
    @Transactional(readOnly = true)
    public ParticipantDtos.TextPreviewResponse preview(ParticipantPrincipal principal, String eventPublicId,
                                                       ParticipantDtos.TextPreviewRequest request) {
        Event event = participantService.requireOwnEvent(principal, eventPublicId);
        participantService.requireActive(principal);
        if (!rateLimiter.tryAcquire("free-text-preview", String.valueOf(principal.participantId()), PREVIEWS_PER_MINUTE,
                Duration.ofMinutes(1))) {
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }
        Long experienceVersionId = event.getCurrentExperienceVersionId();
        if (experienceVersionId == null || !participantService.offers(experienceVersionId, request.frameVersionId())) {
            throw new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED);
        }
        FrameVersion version = frameVersionRepository.findById(request.frameVersionId())
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED));
        ParticipantFreeTextService.Prepared text = freeTextService.prepare(experienceVersionId, version,
                new ParticipantDtos.FreeText(request.text(), request.styleId()));
        return new ParticipantDtos.TextPreviewResponse(request.text(), request.styleId(),
                "data:image/png;base64," + Base64.getEncoder().encodeToString(text.rendered().png()),
                text.x(), text.y(), text.rendered().width(), text.rendered().height(),
                text.spec().canvasWidth(), text.spec().canvasHeight());
    }

    // 접수가 커밋되면 작업이 PNG 를 가져가고, 작업이 확정될 때 원본과 함께 지운다(ComposeJob.inputKeys).
    // 롤백(입력 경합·한도 초과 등)이면 커밋 뒤 콜백이 바로 지운다. 지울 책임을 별도 트랜잭션으로 먼저 커밋하지 않는 이유:
    // 이 트랜잭션이 커넥션을 쥔 채 두 번째 커넥션을 기다리면 접수가 몰릴 때 풀이 마른다. 업로드와 커밋 사이 프로세스가 죽으면
    // 작업 폴더에 PNG 하나가 남는다(작업·자산 행이 없어 보이지 않는다) — 그 드문 고아는 감수한다
    private Asset uploadTextRender(ParticipantFreeTextService.Prepared text, String storageRoot, Event event,
                                   ParticipantPrincipal principal, CaptureRun run) {
        String publicId = Asset.newPublicId();
        String key = storageRoot + ComposeJob.PRIVATE_TEXT_DIR + publicId + ".png";
        byte[] png = text.rendered().png();
        fileStorageService.uploadBytes(key, png, "image/png");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        fileStorageService.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("[자유 문구] 롤백된 접수의 PNG 를 지우지 못했다 key={}", key, e);
                    }
                }
            }
        });
        return assetRepository.save(Asset.serverTextRender(publicId, event.getId(), principal.participantId(), run.getId(),
                key, png.length, text.rendered().width(), text.rendered().height(), text.text().text(), text.style().id(),
                LocalDateTime.now(clock)));
    }

    // 발행본 레이어 위에 문구 PNG 하나 — 칸 크기 그대로(scale 1, 회전 없음)
    private static ComposeSpec withTopLayer(ParticipantFreeTextService.Prepared text, String key) {
        ComposeSpec spec = text.spec();
        int top = spec.layers().stream().mapToInt(ComposeSpec.Layer::zIndex).max().orElse(0) + 1;
        List<ComposeSpec.Layer> layers = new ArrayList<>(spec.layers());
        layers.add(new ComposeSpec.Layer(key, text.x(), text.y(), text.rendered().width(), text.rendered().height(),
                1.0, 0, top, 1.0));
        return new ComposeSpec(spec.canvasWidth(), spec.canvasHeight(), spec.background(), spec.slots(),
                spec.cellCutouts(), layers, spec.cells());
    }

    private static ParticipantDtos.ComposeAccepted accepted(ComposeJob job, ResultTokenResponse token) {
        return new ParticipantDtos.ComposeAccepted(job.getId(), job.getStatus(), token.resultToken(), token.resultExpiresAt());
    }
}
