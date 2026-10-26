package com.harucut.participant.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.repository.FrameVersionRepository;
import com.harucut.frame.version.FrameSpecComposer;
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
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Service
@Transactional
@RequiredArgsConstructor
public class ParticipantComposeService {

    private final ParticipantService participantService;
    private final ComposeJobRepository composeJobRepository;
    private final AssetRepository assetRepository;
    private final FrameVersionRepository frameVersionRepository;
    private final FrameSpecComposer frameSpecComposer;
    private final ComposeService composeService;
    private final ResultService resultService;
    private final EntitlementService entitlementService;

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
        participantService.requireCaptureOpen(event);
        if (!participantService.offers(run.getExperienceVersionId(), request.frameVersionId())) {
            throw new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED);
        }
        FrameVersion version = frameVersionRepository.findById(request.frameVersionId())
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.FRAME_NOT_OFFERED));
        List<Asset> inputs = inputs(principal, run, request.sourceAssetIds());

        ComposeJob job = composeService.accept(ComposeJob.forParticipant(principal.participantId(),
                S3Keys.UPLOAD_ROOT + "events/" + event.getPublicId() + "/runs/" + run.getPublicId() + "/",
                version.getFrameId(), request.idempotencyKey(), inputs.stream().map(Asset::getS3Key).toList(),
                frameSpecComposer.compose(version), event.getId(), run.getId(), run.getExperienceVersionId(),
                version.getId()));
        // 입력은 한 작업에만 — 동시에 다른 작업이 같은 입력을 묶었으면 여기서 진다(트랜잭션 전체가 롤백된다)
        if (assetRepository.bindToJob(inputs.stream().map(Asset::getId).toList(), job.getId()) != inputs.size()) {
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
        if (!request.frameVersionId().equals(job.getFrameVersionId()) || !job.sourceKeys().equals(requestedKeys)) {
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

    private static ParticipantDtos.ComposeAccepted accepted(ComposeJob job, ResultTokenResponse token) {
        return new ParticipantDtos.ComposeAccepted(job.getId(), job.getStatus(), token.resultToken(), token.resultExpiresAt());
    }
}
