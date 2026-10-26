package com.harucut.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.participant.enums.ParticipantChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

// 행사 사진함·복제 API 의 요청·응답 (ADR-0008 §3)
public final class PhotoDtos {

    private PhotoDtos() {
    }

    @Schema(name = "PhotoPage", description = "사진함 한 쪽 — 최신순")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoPage(List<Photo> items, @Schema(description = "다음 쪽 커서. 없으면 마지막 쪽") String nextCursor) {
    }

    @Schema(name = "Photo", description = "사진함의 사진 한 장(합성 작업 하나)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Photo(
            @Schema(description = "사진 id = 합성 작업 id") Long photoId,
            ComposeStatus status,
            OffsetDateTime createdAt,
            ParticipantChannel channel,
            Long frameVersionId,
            @Schema(description = "DONE 일 때만 — presigned GET 5분. 깨지면 목록을 다시 받는다") String thumbnailUrl,
            @Schema(description = "FAILED 일 때만") Failure failure
    ) {
    }

    @Schema(name = "PhotoFailure", description = "실패 사유 — 내부 예외가 아니라 코드")
    public record Failure(
            @Schema(description = "COMPOSE_FAILED · INPUT_MISSING · TIMEOUT") String code,
            @Schema(description = "참가자가 새로 찍어 다시 시도하면 될 수 있는가") boolean retryable
    ) {
    }

    @Schema(name = "EventCloneRequest", description = "행사 복제 — 설정·테마·제공 프레임만 새 초안으로")
    public record CloneRequest(
            @NotBlank @Size(max = 100) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "wedding-1103") String slug
    ) {
    }
}
