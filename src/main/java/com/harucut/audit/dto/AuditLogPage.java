package com.harucut.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "감사 로그 한 쪽 — 최신순")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogPage(List<Item> items, @Schema(description = "다음 쪽 커서. 없으면 마지막 쪽") String nextCursor) {

    @Schema(name = "AuditLogItem")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @Schema(example = "EVENT_PUBLISHED") String action,
            @Schema(description = "행동한 사람의 이름. 시스템·탈퇴 사용자면 키가 없다") String actorName,
            String eventId,
            String targetType,
            String targetId,
            @Schema(description = "행동별 세부(JSON 문자열)") String detail,
            OffsetDateTime createdAt
    ) {
    }
}
