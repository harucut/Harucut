package com.harucut.participant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.participant.enums.ConsentDecision;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.enums.SessionStatus;
import com.harucut.storage.enums.ContentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

// 참가자 API 의 요청·응답 (ADR-0007 §5). 한 파일에 모아 계약을 한눈에 보이게 한다
public final class ParticipantDtos {

    private ParticipantDtos() {
    }

    @Schema(description = "참가자 세션 발급 요청")
    public record SessionRequest(
            @NotNull @Schema(description = "BYOD 만 받는다(부스는 /api/booth/participant-sessions)", example = "BYOD",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            ParticipantChannel channel
    ) {
    }

    @Schema(description = "발급한 참가자 세션 — 토큰 원문은 이 응답에서만 볼 수 있다")
    public record SessionResponse(
            @Schema(description = "X-Participant-Token 헤더로 보낼 토큰") String participantToken,
            @Schema(description = "참가자 public id") String participantId,
            @Schema(description = "동의 전에는 RESTRICTED", example = "RESTRICTED") SessionStatus status,
            @Schema(description = "세션 만료 시각") OffsetDateTime expiresAt
    ) {
    }

    @Schema(description = "동의 결정")
    public record ConsentRequest(
            @NotEmpty @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ConsentItem> items
    ) {
        // EventSettings.ConsentItem(운영자가 쓰는 항목 정의)과 단순 이름이 같다 — 스키마 이름을 따로 준다
        @Schema(name = "ParticipantConsentItem", description = "참가자의 항목별 동의 결정")
        public record ConsentItem(
                @NotBlank @Schema(description = "동의 항목 코드", example = "photo-collection") String code,
                @NotNull @Schema(description = "항목 버전 — 공개 행사 정보의 값 그대로", example = "1") Integer version,
                @NotNull @Schema(description = "AGREED · DECLINED · WITHDRAWN", example = "AGREED") ConsentDecision decision
        ) {
        }
    }

    @Schema(description = "동의 후 세션 상태")
    public record ConsentResponse(
            @Schema(description = "필수 항목을 모두 현재 버전으로 동의했으면 ACTIVE", example = "ACTIVE") SessionStatus status
    ) {
    }

    @Schema(description = "세션 복원 — 새로고침·재방문")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionView(
            String participantId,
            String eventId,
            String eventSlug,
            ParticipantChannel channel,
            SessionStatus status,
            OffsetDateTime expiresAt,
            @Schema(description = "아직 현재 버전으로 동의하지 않은 필수 항목") List<PendingConsent> pendingConsents,
            @Schema(description = "진행 중인 촬영 회차 — 없으면 키가 없다") RunView activeCaptureRun
    ) {
        public record PendingConsent(String code, int version) {
        }
    }

    @Schema(description = "촬영 회차")
    public record RunView(
            @Schema(description = "회차 public id") String captureRunId,
            @Schema(description = "부스 화면 대조용 상태 버전") int stateVersion,
            @Schema(description = "회차 만료 시각") OffsetDateTime expiresAt
    ) {
    }

    @Schema(description = "새 촬영 회차")
    public record RunStartResponse(
            String captureRunId,
            @Schema(description = "서버가 부여한 경험 버전 번호 — 참가자가 고르지 않는다") int experienceVersion,
            int stateVersion,
            OffsetDateTime expiresAt
    ) {
    }

    @Schema(description = "촬영 입력 업로드 의도")
    public record CaptureUploadRequest(
            @NotBlank @Schema(description = "회차 public id", requiredMode = Schema.RequiredMode.REQUIRED) String captureRunId,
            @NotNull @Min(0) @Max(3) @Schema(description = "몇 번째 칸(0~3)", requiredMode = Schema.RequiredMode.REQUIRED) Integer slotIndex,
            @NotNull @Schema(description = "JPEG · PNG", example = "JPEG", requiredMode = Schema.RequiredMode.REQUIRED) ContentType contentType,
            @NotNull @Positive @Max(10485760) @Schema(description = "바이트 크기(10MB 이하)", requiredMode = Schema.RequiredMode.REQUIRED) Long fileSize
    ) {
    }

    // 스키마 이름을 따로 준다 — 단순 이름이 개인 합성 media.dto.ComposeRequest 와 같아 springdoc 이 하나로 합친다
    @Schema(name = "EventComposeRequest", description = "행사 합성 접수")
    public record ComposeRequest(
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String captureRunId,
            @NotNull @Schema(description = "이 회차에 제공된 프레임 발행본", requiredMode = Schema.RequiredMode.REQUIRED) Long frameVersionId,
            @NotNull @Size(min = 4, max = 4) @Schema(description = "칸 순서대로 촬영 입력 4개", requiredMode = Schema.RequiredMode.REQUIRED)
            List<@NotBlank String> sourceAssetIds,
            @NotBlank @Size(max = 64) @Schema(description = "요청마다 새 UUID. 같은 키 재요청은 기존 작업을 돌려준다", requiredMode = Schema.RequiredMode.REQUIRED)
            String idempotencyKey,
            @Valid @Schema(description = "자유 문구(행사가 허용할 때만) — 없으면 프레임 그대로 합성한다") FreeText freeText
    ) {
        public ComposeRequest(String captureRunId, Long frameVersionId, List<String> sourceAssetIds, String idempotencyKey) {
            this(captureRunId, frameVersionId, sourceAssetIds, idempotencyKey, null);
        }
    }

    @Schema(description = "참가자 자유 문구 — 문자열과 스타일 id 만. 서버가 구워 합성한다(R5 D06)")
    public record FreeText(
            @NotNull @Size(max = 200) @Schema(description = "문구 — 줄바꿈(\\n)으로만 줄을 나눈다", example = "민지 ♥ 준호",
                    requiredMode = Schema.RequiredMode.REQUIRED) String text,
            @NotBlank @Size(max = 40) @Schema(description = "행사가 고른 스타일 id", example = "pretendard-bold-dark",
                    requiredMode = Schema.RequiredMode.REQUIRED) String styleId
    ) {
    }

    @Schema(description = "자유 문구 미리보기 요청")
    public record TextPreviewRequest(
            @NotNull @Schema(description = "제공된 프레임 발행본", requiredMode = Schema.RequiredMode.REQUIRED) Long frameVersionId,
            @NotNull @Size(max = 200) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
            @NotBlank @Size(max = 40) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String styleId
    ) {
    }

    @Schema(description = "자유 문구 미리보기 — 합성 때와 같은 서버 렌더. text·styleId 가 지금 입력과 다르면 늦은 응답이니 버린다")
    public record TextPreviewResponse(
            @Schema(description = "요청에 보낸 문구 그대로") String text,
            @Schema(description = "요청에 보낸 스타일 id 그대로") String styleId,
            @Schema(description = "구운 PNG — data:image/png;base64,…") String image,
            @Schema(description = "캔버스 px 좌표") int x,
            int y,
            int width,
            int height,
            int canvasWidth,
            int canvasHeight
    ) {
    }

    @Schema(description = "접수한 행사 합성 — 결과 토큰 원문은 이 응답에서만 볼 수 있다")
    public record ComposeAccepted(
            Long jobId,
            ComposeStatus status,
            @Schema(description = "결과 링크 토큰(/r#<token>). 같은 키 재요청이면 새 토큰이 하나 더 발급된다") String resultToken,
            OffsetDateTime resultExpiresAt
    ) {
    }

    @Schema(description = "이 행사에서 만든 내 결과")
    public record MyResult(Long jobId, ComposeStatus status, OffsetDateTime createdAt) {
    }
}
