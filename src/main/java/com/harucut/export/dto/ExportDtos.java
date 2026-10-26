package com.harucut.export.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.export.enums.ExportStatus;
import com.harucut.participant.enums.ParticipantChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

// 묶음 다운로드 API 의 요청·응답 (ADR-0008 §4)
public final class ExportDtos {

    private ExportDtos() {
    }

    @Schema(name = "ExportRequest", description = "photoIds 또는 filter 중 하나. 둘 다 없으면 행사의 완성 사진 전부. photoIds 를 빈 목록으로 보내면 400 EXPORT-003")
    public record ExportRequest(
            @Size(max = 2000) @Schema(description = "고른 사진 id — 이 순서로 담는다") List<Long> photoIds,
            @Valid ExportFilter filter
    ) {
    }

    @Schema(name = "ExportFilter")
    public record ExportFilter(OffsetDateTime from, OffsetDateTime to, ParticipantChannel channel, Long frameVersionId) {
    }

    @Schema(name = "Export", description = "묶음 다운로드 작업")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Export(
            String exportId,
            @Schema(description = "PENDING · RUNNING · DONE · FAILED · EXPIRED") ExportStatus status,
            int photoCount,
            Long sizeBytes,
            @Schema(description = "DONE 일 때 — 이 시각 뒤에는 받을 수 없다") OffsetDateTime expiresAt,
            OffsetDateTime createdAt
    ) {
    }
}
