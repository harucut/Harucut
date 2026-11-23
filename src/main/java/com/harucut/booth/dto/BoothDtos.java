package com.harucut.booth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.enums.BoothDeviceStatus;
import com.harucut.event.dto.PublicEventResponse;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.participant.enums.CaptureRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

// 부스 API 의 요청·응답 (ADR-0007 §4·§7). 스키마 이름은 다른 DTO 와 겹치지 않게 Booth 접두어를 붙인다
public final class BoothDtos {

    private BoothDtos() {
    }

    @Schema(name = "BoothDeviceCreateRequest", description = "부스 기기 등록")
    public record DeviceCreateRequest(
            @NotBlank @Size(max = 50) @Schema(description = "운영자가 알아볼 이름(중복 허용)", example = "입구 부스",
                    requiredMode = Schema.RequiredMode.REQUIRED) String name
    ) {
    }

    @Schema(name = "BoothDeviceResponse", description = "부스 기기 — pairingCode 는 등록·재발급 응답에만 실린다")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceResponse(
            @Schema(description = "기기 public id") String deviceId,
            @Schema(description = "이름", example = "입구 부스") String name,
            @Schema(description = "PENDING_PAIRING · ACTIVE · REVOKED", example = "PENDING_PAIRING") BoothDeviceStatus status,
            @Schema(description = "연결 코드(XXXX-XXXX, 10분). 기기 화면에 입력한다", example = "K7QM-3XPA") String pairingCode,
            @Schema(description = "연결 코드 만료 시각") OffsetDateTime pairingExpiresAt,
            @Schema(description = "기기가 마지막으로 상태를 확인한 시각") OffsetDateTime lastSeenAt
    ) {
        public static DeviceResponse of(BoothDevice device, String pairingCode, OffsetDateTime pairingExpiresAt,
                                        OffsetDateTime lastSeenAt) {
            return new DeviceResponse(device.getPublicId(), device.getName(), device.getStatus(), pairingCode,
                    pairingExpiresAt, lastSeenAt);
        }
    }

    @Schema(name = "BoothPairingRedeemRequest", description = "연결 코드 교환")
    public record RedeemRequest(
            @NotBlank @Size(max = 20) @Schema(description = "XXXX-XXXX — 하이픈·대소문자·공백은 무시한다", example = "K7QM-3XPA",
                    requiredMode = Schema.RequiredMode.REQUIRED) String code
    ) {
    }

    @Schema(name = "BoothPairingRedeemResponse", description = "기기 토큰 — 원문은 이 응답에서만 볼 수 있다")
    public record RedeemResponse(
            @Schema(description = "X-Booth-Token 헤더로 보낼 토큰") String boothToken,
            @Schema(description = "기기 public id") String deviceId,
            @Schema(description = "배정 행사 public id") String eventId
    ) {
    }

    @Schema(name = "BoothMeResponse", description = "기기 상태와 배정 행사")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeResponse(
            String deviceId,
            String name,
            BoothDeviceStatus status,
            @Schema(description = "GET /api/public/events/{slug} 와 같은 모양. 게시 전·보관 종료면 키가 없다(대기 화면)")
            PublicEventResponse event
    ) {
    }

    @Schema(name = "BoothRunView", description = "기기의 지금 회차")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunView(
            String captureRunId,
            @Schema(description = "닫을 때 그대로 보낸다") int stateVersion,
            CaptureRunStatus status,
            @Schema(description = "version 이 stateVersion 과 같을 때만, 합성 작업이 있으면") Job job
    ) {
        @Schema(name = "BoothRunJob")
        public record Job(Long jobId, ComposeStatus status) {
        }
    }

    @Schema(name = "BoothRunStateRequest", description = "회차를 바꾸는 요청이 대조할 stateVersion")
    public record RunStateRequest(
            @NotNull @Schema(description = "current 응답의 stateVersion", requiredMode = Schema.RequiredMode.REQUIRED) Integer stateVersion
    ) {
    }
}
