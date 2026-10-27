package com.harucut.media.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.frame.cell.FreeCellsGate;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.service.FrameService;
import com.harucut.media.compose.ComposeMetrics;
import com.harucut.media.compose.ComposeRequestedEvent;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.compose.ComposeSpecAssembler;
import com.harucut.media.compose.ImageFormat;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.media.dto.ComposeRequest;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.media.util.DisplayNames;
import com.harucut.push.service.PushMessage;
import com.harucut.push.service.PushRequestedEvent;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class ComposeService {

    private final UserRepository userRepository;
    private final ComposeJobRepository composeJobRepository;
    private final UserMediaRepository userMediaRepository;
    private final FrameService frameService;
    private final ComposeSpecAssembler composeSpecAssembler;
    private final FreeCellsGate freeCellsGate;
    private final S3Deleter s3Deleter;
    private final ApplicationEventPublisher eventPublisher;
    private final ComposeMetrics composeMetrics;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;

    // ComposeJob.spec_json 컬럼 길이와 같아야 한다
    private static final int SPEC_JSON_MAX_CHARS = 8000;

    public ComposeJobResponse requestCompose(String publicId, ComposeRequest request) {
        User user = getUser(publicId);

        // 멱등 재생: 더블클릭·재시도는 기존 Job을 그대로 받는다 — 새 실행 없음.
        // 정확히 동시에 온 중복은 (user, key) unique가 막는다 (한쪽은 실패하지만 데이터는 안전)
        // 원본은 정규화된 key로 저장하고, 검사도 그 값으로 한다 — URL로 감싼 남의 key 차단.
        // 재생 판정(지문 비교)도 저장된 값과 같은 모양으로 비교해야 하므로 먼저 정규화한다
        List<String> sourceKeys = request.sourceKeys().stream()
                .map(S3Keys::normalizeToKey)
                .toList();

        Optional<ComposeJob> existing = composeJobRepository.findByIdempotencyScopeAndIdempotencyKey(
                ComposeJob.personalScope(user.getId()), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), request, sourceKeys);
        }

        validateSourceOwnership(publicId, sourceKeys);

        Frame frame = frameService.getComposableFrame(user, request.frameId());
        freeCellsGate.requireRenderable(frame);   // 자유 사진 칸 — 렌더 스위치 전에는 409 FRAME-008
        ComposeSpec spec = composeSpecAssembler.assemble(frame, request.backgroundColor());

        ComposeJob job = accept(ComposeJob.create(user, frame.getId(), request.idempotencyKey(), sourceKeys, spec));
        return ComposeJobResponse.from(job);
    }

    /**
     * 준비된 작업을 접수한다 — 저장하고, 커밋 후 실행할 이벤트를 발행한다. 개인 합성과 행사 참가자 합성이 같은 입구를 쓴다.
     * spec_json 상한(8000자)도 여기서 본다.
     */
    public ComposeJob accept(ComposeJob prepared) {
        assertSpecFits(prepared.getSpec());
        ComposeJob job = composeJobRepository.save(prepared);

        // 실행은 커밋 후에 시작된다(AFTER_COMMIT 리스너) — 커밋 전에 실행 스레드가 뜨면
        // 아직 안 보이는 Job을 상대로 결과를 기록하려다 실패한다
        eventPublisher.publishEvent(new ComposeRequestedEvent(
                job.getId(), job.getSpec(), job.sourceKeys(),
                resultKeyFor(job), thumbnailKeyFor(job),
                RESULT_FORMAT));
        composeMetrics.requestAccepted();
        return job;
    }

    // 정확히 동시에 온 중복 요청의 패자가 여기로 온다. requestCompose 의 INSERT 가
    // (user, idempotency_key) 유니크에 걸려 그 트랜잭션은 롤백됐다 — 새 트랜잭션에서 승자의 Job 을
    // 다시 읽어 지문을 비교한다. 승자가 없으면 다른 제약 위반이라 null 을 돌려 호출자가 원래 예외를 던진다
    @Transactional(readOnly = true)
    public ComposeJobResponse replayAfterConflict(String publicId, ComposeRequest request) {
        User user = getUser(publicId);
        List<String> sourceKeys = request.sourceKeys().stream()
                .map(S3Keys::normalizeToKey)
                .toList();
        return composeJobRepository.findByIdempotencyScopeAndIdempotencyKey(
                        ComposeJob.personalScope(user.getId()), request.idempotencyKey())
                .map(job -> replay(job, request, sourceKeys))
                .orElse(null);
    }

    // 멱등 재생: 같은 키 + 같은 요청이면 기존 Job 을 그대로, 같은 키 + 다른 요청이면 409.
    // 지문 컬럼을 따로 두지 않고 Job 이 이미 저장한 값(프레임·원본 4장·배경색)과 비교한다
    private ComposeJobResponse replay(ComposeJob job, ComposeRequest request, List<String> sourceKeys) {
        if (!sameRequest(job, request.frameId(), sourceKeys, request.backgroundColor())) {
            throw new BusinessException(MediaErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        composeMetrics.requestReplayed();
        return ComposeJobResponse.from(job);
    }

    // 배경색은 요청에 있을 때만 비교한다. 저장된 spec 의 배경은 "덮어쓴 색"과 "프레임 원래 색"을
    // 구분하지 못한다 — 색을 안 보낸 요청을 프레임 색과 비교하면, 그사이 프레임이 수정됐을 때
    // 정상 재요청이 409 가 된다
    static boolean sameRequest(ComposeJob job, Long frameId, List<String> sourceKeys,
                               String backgroundColor) {
        if (!job.getFrameId().equals(frameId) || !job.sourceKeys().equals(sourceKeys)) {
            return false;
        }
        if (backgroundColor == null) {
            return true;
        }
        return job.getSpec().background() instanceof BackgroundAttributes.Color color
                && color.value().equalsIgnoreCase(backgroundColor);
    }

    // spec_json 은 8000자 컬럼이다. 넘으면 INSERT 가 DB 오류(500)로 죽는다 — 그 전에 설명 가능한 400 으로 끊는다.
    // 프레임 저장 시점에도 같은 상한을 보지만, 상한이 생기기 전에 저장된 프레임이 여기로 올 수 있다
    private void assertSpecFits(ComposeSpec spec) {
        int chars = objectMapper.writeValueAsString(spec).length();
        if (chars > SPEC_JSON_MAX_CHARS) {
            throw new BusinessException(FrameErrorCode.SPEC_TOO_LARGE, "합성 스펙 " + chars + "자 > " + SPEC_JSON_MAX_CHARS);
        }
    }

    // 게이지용. 재실행 배치가 30초마다 한 번만 부른다 — 스크레이프마다 세면
    // 프로메테우스 주기가 DB 부하를 정하게 된다
    @Transactional(readOnly = true)
    public long countPending() {
        return composeJobRepository.countByStatus(ComposeStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public ComposeJobResponse getJob(String publicId, Long jobId) {
        User user = getUser(publicId);
        ComposeJob job = composeJobRepository.findByIdAndUser(jobId, user)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        return ComposeJobResponse.from(job);
    }

    // ── 워커가 실행 결과를 기록할 때 부르는 전이 — 각자 자기 트랜잭션에서 돈다 ──

    // 전이를 이긴 호출만 true 다 — 미디어 생성과 원본 삭제가 그 안에서만 일어난다.
    // 재실행 경합·SQS 재배달로 같은 Job 의 통지가 두 번 와도 두 번째는 false 로 끝난다
    public boolean completeJob(Long jobId, String resultKey, String thumbnailKey) {
        ComposeJob job = composeJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("합성 Job이 사라졌다: " + jobId));
        if (job.getStatus() != ComposeStatus.PENDING) {
            return false;   // 재실행 경합 — 먼저 끝난 쪽이 이미 기록했다
        }
        if (composeJobRepository.markDone(jobId, resultKey,
                ComposeStatus.PENDING, ComposeStatus.DONE) == 0) {
            return false;   // 읽은 뒤 다른 통지가 먼저 전이했다
        }

        // 개인 작업만 보관함(user_media)에 들어간다. 행사 참가자 작업은 결과 토큰으로 받는다(ResultAccess, P2A)
        if (job.getUser() != null) {
            UserMedia media = userMediaRepository.save(UserMedia.of(job.getUser(), resultKey,
                    thumbnailKey, DisplayNames.resolve(null, resultKey, LocalDateTime.now(clock))));
            composeJobRepository.attachMedia(jobId, media.getId());
            eventPublisher.publishEvent(new PushRequestedEvent(job.getUser().getId(), PushMessage.composeDone(jobId)));
        }
        if (job.getEventId() != null) {
            // 사용량 예약을 확정한다 — 전이를 이긴 이 트랜잭션에서만(늦은 통지는 위에서 false 로 끝났다)
            entitlementService.commit(jobId);
            // 합성 중에 사진함에서 삭제 요청됐다 — 방금 만든 결과·썸네일을 남기지 않는다.
            // 잠금 읽기(FOR UPDATE)라 엇갈려 커밋된 삭제 요청을 놓치지 않는다(삭제 쪽도 잠금 읽기로 결과 key 를 다시 본다)
            if (composeJobRepository.lockDeletedAt(jobId).stream().anyMatch(Objects::nonNull)) {
                s3Deleter.deleteAfterCommit(List.of(resultKey, thumbnailKey));
            }
        }
        // 원본은 성공 시 삭제 — 결과만 보관함에 남는다.
        // 원본은 합성 재료일 뿐 사용자의 보관 대상이 아니라 저장 비용만 먹는다
        s3Deleter.deleteAfterCommit(job.sourceKeys());
        return true;
    }

    // 최종 실패(RetriesExhausted)에서만 불린다 — 일시적 실패는 PENDING 으로 남아 재실행된다.
    // 원본도 지운다: 같은 멱등키의 재요청은 이 FAILED Job 을 재생할 뿐 원본을 다시 읽지 않고,
    // 다시 시도하려면 새 키로 원본을 새로 올려야 한다. 남겨 두면 아무도 안 쓰는 4장이 버킷에 쌓인다
    public boolean failJob(Long jobId, String reason) {
        return failJob(jobId, reason, ComposeFailureCode.UNKNOWN);
    }

    public boolean failJob(Long jobId, String reason, ComposeFailureCode code) {
        Optional<ComposeJob> job = composeJobRepository.findById(jobId);
        if (job.isEmpty()) {
            return false;
        }
        if (composeJobRepository.markFailed(jobId, ComposeJob.truncateFailureReason(reason), code,
                ComposeStatus.PENDING, ComposeStatus.FAILED) == 0) {
            return false;
        }
        if (job.get().getEventId() != null) {
            entitlementService.release(jobId);   // 예약 수량을 돌려준다(돈의 환불이 아니다)
        }
        // 개인 작업만 푸시한다 — 행사 참가자는 계정이 없다
        if (job.get().getUser() != null) {
            eventPublisher.publishEvent(new PushRequestedEvent(job.get().getUser().getId(),
                    PushMessage.composeFailed(jobId, code.name())));
        }
        s3Deleter.deleteAfterCommit(job.get().sourceKeys());
        return true;
    }

    // REQUIRES_NEW 가 필수다. 이 메서드의 호출자 중 하나가 AFTER_COMMIT 리스너(ComposeWorker)인데,
    // 스프링은 그 단계를 afterCompletion(STATUS_COMMITTED) 에서 실행한다 — EntityManagerHolder 는
    // 아직 스레드에 묶여 있고 transactionActive 도 true 라, REQUIRED 로 두면 "이미 커밋된" 트랜잭션에
    // 참여해 버린다. Hibernate 는 커밋된 세션의 벌크 UPDATE 를 거부한다:
    //     TransactionRequiredException: No active transaction for update or delete query
    // 그 예외는 TransactionSynchronizationUtils 가 삼켜서 202 는 그대로 나가고 지표에도 안 잡힌다.
    // 결과는 "즉시 접수가 통째로 죽고 모든 합성이 30초 재실행 배치로만 도는" 상태였다.
    // 회귀 방지: ComposeClaimTransactionTest
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long jobId, Duration staleAfter) {
        LocalDateTime now = LocalDateTime.now(clock);
        return composeJobRepository.claim(jobId, now, now.minus(staleAfter)) == 1;
    }

    // 재실행 대상을 실행 payload 로 바꿔 내보낸다. 엔티티를 밖으로 내보내지 않는 이유는
    // 워커가 트랜잭션 밖에서 돌기 때문이다 — 지연 로딩이 거기서 터진다
    @Transactional(readOnly = true)
    public List<ComposeRequestedEvent> findStalled(Duration staleAfter, int limit) {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(staleAfter);
        return composeJobRepository.findStalled(staleBefore, PageRequest.of(0, limit)).stream()
                .map(job -> new ComposeRequestedEvent(job.getId(), job.getSpec(), job.sourceKeys(),
                        resultKeyFor(job), thumbnailKeyFor(job), RESULT_FORMAT))
                .toList();
    }

    // 결과물 저장 포맷의 유일한 출처. 키 확장자와 Lambda로 보내는 지시가 **둘 다 여기서** 나온다.
    // 나뉘어 있으면 한쪽만 바꾸는 실수가 .jpg 안에 PNG 바이트를 넣고, 그건 S3에 올라간 뒤에야 보인다.
    //
    // JPEG인 근거는 docs/perf-10k-dau.md §3 — 결과물은 투명 픽셀이 없는 사진이라 PNG의
    // 강점(무손실·알파)을 못 쓰면서 크기만 9~10배였다(실측 GRID 25.5MB → 2.5MB).
    // 기존 .png 결과물은 그대로 둔다 — 읽기 경로에 확장자 가정이 없어 섞여도 동작한다.
    // ⚠️ 이 값을 바꾸는 커밋은 Lambda 선배포가 전제다 — 옛 Lambda는 outputFormat을 몰라
    //    PNG로 그리므로, 서버가 먼저 나가면 .jpg 키에 PNG 바이트가 들어간다
    static final ImageFormat RESULT_FORMAT = ImageFormat.JPEG;

    // Job당 결정적 결과 key — 재실행이 겹쳐도 같은 객체를 덮어쓰므로 고아 파일이 안 생기고,
    // UserMedia의 s3Key unique가 중복 행의 최종 방어선이 된다
    public static String resultKeyFor(ComposeJob job) {
        return job.getStorageRoot() + "fourcuts/job-" + job.getId() + "." + RESULT_FORMAT.extension();
    }

    // 썸네일도 같은 원칙의 결정적 key — 원본 옆에서 함께 살고 함께 지워진다
    public static String thumbnailKeyFor(ComposeJob job) {
        return job.getStorageRoot() + "fourcuts/job-" + job.getId() + "-thumb.jpg";
    }

    // 남의 원본으로 합성 못 한다 — 정규화된 key의 내 prefix 검사, 403.
    //
    // 내 폴더인 것만으로는 부족하다: 같은 prefix 아래에 profile/·frames/·components/가 함께 살고,
    // 합성에 성공하면 여기 실린 key가 전부 삭제된다(completeJob). 사용자가 자기 스티커 key를
    // 원본 자리에 넣으면 그 스티커가 지워지고, 그걸 쓰는 프레임의 이후 합성이 전부 실패한다.
    // 발급 경로(FourcutSourceUploadPathStrategy)가 이미 이 폴더만 내주므로 정상 요청은 안 막힌다
    private static final String SOURCE_FOLDER = "fourcuts/sources/";

    private void validateSourceOwnership(String publicId, List<String> sourceKeys) {
        String sourceRoot = S3Keys.userRoot(publicId) + SOURCE_FOLDER;
        for (String sourceKey : sourceKeys) {
            S3Keys.assertOwnedBy(sourceKey, publicId);
            if (!sourceKey.startsWith(sourceRoot)) {
                throw new BusinessException(GlobalErrorCode.FORBIDDEN);
            }
        }
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }
}
