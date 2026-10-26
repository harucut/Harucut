package com.harucut.participant.controller;

import com.harucut.asset.dto.AssetResponse;
import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.service.AssetService;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.participant.service.ParticipantComposeService;
import com.harucut.participant.service.ParticipantService;
import com.harucut.result.dto.DownloadUrlResponse;
import com.harucut.result.dto.ResultTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "행사 참가")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/participant")
public class ParticipantController {

    private final ParticipantService participantService;
    private final ParticipantComposeService participantComposeService;

    @Operation(summary = "세션 복원", description = "새로고침·재방문 때 토큰이 아직 유효한지, 남은 필수 동의와 진행 중 회차를 본다.")
    @ApiErrors("PART-001: 토큰이 없거나 만료·회수")
    @GetMapping("/session")
    public Response<ParticipantDtos.SessionView> session(@AuthenticationPrincipal ParticipantPrincipal principal) {
        return Response.ok(participantService.session(principal));
    }

    @Operation(summary = "참가 동의 기록", description = "필수 항목을 모두 현재 버전으로 AGREED 하면 ACTIVE. 필수를 거부·철회하면 RESTRICTED 로 돌아간다.")
    @ApiErrors({"PART-001: 세션 무효", "GEN-002: 현재 행사의 항목·버전이 아니다", "GEN-031: 내 행사가 아니다"})
    @PostMapping("/events/{eventId}/consents")
    public Response<ParticipantDtos.ConsentResponse> consent(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                             @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                             @RequestBody @Valid ParticipantDtos.ConsentRequest request) {
        return Response.ok(participantService.consent(principal, eventId, request));
    }

    @Operation(summary = "새 촬영 회차", description = "서버가 현재 경험 버전을 부여한다. 회차는 기본 30분.")
    @ApiErrors({"PART-001: 세션 무효", "PART-002: 필수 동의 전", "EVENT-001: 지금 촬영할 수 없다", "GEN-031: 내 행사가 아니다"})
    @PostMapping("/events/{eventId}/capture-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ParticipantDtos.RunStartResponse> startRun(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                               @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(participantService.startRun(principal, eventId));
    }

    @Operation(summary = "회차에 제공된 발행 프레임", description = "그 회차의 경험 버전이 제공한 프레임만(FrameSpec v2).")
    @ApiErrors({"PART-004: 이 회차에 제공되지 않은 프레임", "PART-003: 내 회차가 아니다"})
    @GetMapping("/events/{eventId}/frame-versions/{frameVersionId}")
    public Response<FrameVersionResponse> frame(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                @Parameter(description = "프레임 발행본 ID") @PathVariable Long frameVersionId,
                                                @Parameter(description = "회차 public id") @RequestParam String captureRunId) {
        return Response.ok(participantService.offeredFrame(principal, eventId, frameVersionId, captureRunId));
    }

    @Operation(summary = "촬영 입력 업로드 의도",
            description = "용도는 항상 촬영 입력(CAPTURE_INPUT). 갤러리 사진·스티커·글자 이미지 업로드는 이 경로에 없다. PUT URL 은 기본 10분.")
    @ApiErrors({"PART-002: 필수 동의 전", "PART-003: 회차가 활성 상태가 아니다", "GEN-051: JPEG·PNG 가 아니다"})
    @PostMapping("/events/{eventId}/upload-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<UploadIntentResponse> uploadIntent(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                       @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                       @RequestBody @Valid ParticipantDtos.CaptureUploadRequest request) {
        return Response.ok(participantService.createUploadIntent(principal, eventId, request));
    }

    @Operation(summary = "촬영 입력 업로드 완료 확인", description = "자산 complete 와 같은 검증. 실패는 422 + 코드 + data.")
    @ApiErrors({"ASSET-001: 객체가 없다", "ASSET-002: 크기", "ASSET-003: 형식·디코드", "ASSET-004: 픽셀 상한",
            "ASSET-006: 검증이 밀려 있다", "PART-002: 필수 동의 전", "GEN-031: 내 입력이 아니다"})
    @PostMapping("/assets/{assetId}/complete")
    public ResponseEntity<Response<AssetResponse>> complete(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                            @Parameter(description = "자산 public id") @PathVariable String assetId) {
        AssetService.CompleteResult result = participantService.completeUpload(principal, assetId);
        if (result.rejection() != null) {
            return ResponseEntity.status(result.rejection().getHttpStatus())
                    .body(Response.error(result.rejection(), result.asset()));
        }
        return ResponseEntity.ok(Response.ok(result.asset()));
    }

    @Operation(summary = "행사 합성 접수 + 결과 토큰",
            description = """
                    202. 결과 토큰 원문은 이 응답에서만 볼 수 있다 — 결과 링크는 `/r#<token>`.
                    같은 idempotencyKey 재요청은 기존 작업을 돌려주고 **새 결과 토큰을 하나 더** 발급한다(이전 것도 유효).
                    같은 키에 다른 프레임·입력이면 409 `MEDIA-001`.
                    """)
    @ApiErrors({"PART-002: 필수 동의 전", "PART-003: 회차가 활성 상태가 아니다", "PART-004: 제공되지 않은 프레임",
            "PART-005: 쓸 수 없는 입력(미검증·다른 회차·이미 다른 작업에 묶임)", "EVENT-001: 지금 촬영할 수 없다",
            "MEDIA-001: 같은 키에 다른 요청", "FRAME-004: 합성 스펙 크기 초과", "GEN-031: 내 행사가 아니다"})
    @PostMapping("/events/{eventId}/compose-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response<ParticipantDtos.ComposeAccepted> compose(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                             @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                             @RequestBody @Valid ParticipantDtos.ComposeRequest request) {
        try {
            return Response.ok(participantComposeService.submit(principal, eventId, request));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 요청이 정확히 동시에 와서 (scope, key) 유니크에 졌다 — 새 트랜잭션에서 승자를 재생
            ParticipantDtos.ComposeAccepted winner = participantComposeService.replayAfterConflict(principal, eventId, request);
            if (winner == null) {
                throw e;
            }
            return Response.ok(winner);
        }
    }

    @Operation(summary = "내 합성 작업 상태")
    @ApiErrors("GEN-031: 내 작업이 아니다")
    @GetMapping("/compose-jobs/{jobId}")
    public Response<ComposeJobResponse> job(@AuthenticationPrincipal ParticipantPrincipal principal,
                                            @Parameter(description = "작업 ID") @PathVariable Long jobId) {
        return Response.ok(participantService.job(principal, jobId));
    }

    @Operation(summary = "이 행사에서 만든 내 결과", description = "개인 휴대폰 세션만. 공용 부스에는 이전 목록을 열지 않는다.")
    @ApiErrors({"PART-006: 부스 세션", "GEN-031: 내 행사가 아니다"})
    @GetMapping("/events/{eventId}/results")
    public Response<List<ParticipantDtos.MyResult>> results(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                            @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(participantService.myResults(principal, eventId));
    }

    @Operation(summary = "내 결과 다운로드 URL", description = "개인 휴대폰 세션만. 결과 API 와 같은 보관·준비 검사(5분 URL).")
    @ApiErrors({"PART-006: 부스 세션", "RESULT-002: 보관 기한 지남", "RESULT-003: 아직 준비 안 됨", "GEN-031: 내 작업이 아니다"})
    @PostMapping("/compose-jobs/{jobId}/download")
    public Response<DownloadUrlResponse> download(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                  @Parameter(description = "작업 ID") @PathVariable Long jobId) {
        return Response.ok(participantService.download(principal, jobId));
    }

    @Operation(summary = "공유 링크용 결과 토큰 새로 발급", description = "개인 휴대폰 세션만. 이전 토큰은 유지된다.")
    @ApiErrors({"PART-006: 부스 세션", "GEN-031: 내 작업이 아니다"})
    @PostMapping("/compose-jobs/{jobId}/result-access")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ResultTokenResponse> resultAccess(@AuthenticationPrincipal ParticipantPrincipal principal,
                                                      @Parameter(description = "작업 ID") @PathVariable Long jobId) {
        return Response.ok(participantService.newResultAccess(principal, jobId));
    }
}
